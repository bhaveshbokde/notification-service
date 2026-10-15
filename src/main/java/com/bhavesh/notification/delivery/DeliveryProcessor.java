package com.bhavesh.notification.delivery;

import com.bhavesh.notification.common.exception.InvalidStateTransitionException;
import com.bhavesh.notification.notification.domain.Notification;
import com.bhavesh.notification.notification.domain.NotificationAttempt;
import com.bhavesh.notification.notification.repository.NotificationAttemptRepository;
import com.bhavesh.notification.notification.repository.NotificationRepository;
import com.bhavesh.notification.provider.NotificationProvider;
import com.bhavesh.notification.provider.ProviderRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Delivers one notification, and records what happened.
 *
 * <h2>The three-transaction shape, and why</h2>
 * <ol>
 *   <li><strong>Claim</strong> — load the notification, move it to PROCESSING,
 *       increment the attempt count. Commits immediately.</li>
 *   <li><strong>Deliver</strong> — call the provider. <em>No transaction.</em></li>
 *   <li><strong>Record</strong> — write the attempt row and the final status.
 *       Commits.</li>
 * </ol>
 *
 * <p>Step 2 must not be inside a transaction. A provider call is a network
 * round trip to someone else's infrastructure; holding a database connection
 * open across it means a provider having a bad day exhausts the connection pool
 * and takes down the API as well. With a 30-second provider timeout and a
 * 16-connection pool, sixteen slow sends is all it takes.
 *
 * <p>Committing the claim separately also makes the claim <em>visible</em>,
 * which is what stops a concurrent duplicate event from processing the same
 * notification.
 *
 * <h2>Three independent defences against duplicate processing</h2>
 * Kafka gives at-least-once delivery and the outbox can republish, so a
 * duplicate event is normal operation rather than an anomaly:
 * <ol>
 *   <li>{@link Notification#beginProcessing} refuses unless the status is
 *       claimable — PROCESSING and the terminal states are not.</li>
 *   <li>{@code @Version} on the notification makes a concurrent claim fail with
 *       an optimistic lock error rather than silently overwriting.</li>
 *   <li>{@code uq_attempts_notification_number} makes a duplicate attempt row
 *       impossible even if the first two were somehow passed.</li>
 * </ol>
 * All three are expected to fire occasionally; none is treated as an error.
 *
 * <h2>What this cannot promise</h2>
 * Exactly-once <em>processing</em> is achievable and is what the defences above
 * deliver. Exactly-once <em>delivery to the provider</em> is not: if the
 * process dies after the provider accepts the message but before the attempt
 * row commits, the retry sends it again. Where a provider supports a
 * client-supplied idempotency key the adapter passes the notification id, which
 * closes the gap on the provider's side. Where it does not, duplicates remain
 * possible and that is documented rather than hidden.
 */
@Component
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);
    private static final String MDC_NOTIFICATION_ID = "notificationId";

    private final NotificationRepository notificationRepository;
    private final NotificationAttemptRepository attemptRepository;
    private final ProviderRegistry providers;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    private final Counter sentCounter;
    private final Counter failedCounter;
    private final Counter retriedCounter;
    private final Counter duplicateCounter;
    private final Timer deliveryTimer;

    public DeliveryProcessor(NotificationRepository notificationRepository,
                             NotificationAttemptRepository attemptRepository,
                             ProviderRegistry providers,
                             RetryPolicy retryPolicy,
                             MeterRegistry meterRegistry,
                             Clock clock) {
        this.notificationRepository = notificationRepository;
        this.attemptRepository = attemptRepository;
        this.providers = providers;
        this.retryPolicy = retryPolicy;
        this.clock = clock;

        this.sentCounter = Counter.builder("notification.delivery.sent")
                .description("Notifications accepted by a provider").register(meterRegistry);
        this.failedCounter = Counter.builder("notification.delivery.failed")
                .description("Notifications that failed permanently or exhausted retries")
                .register(meterRegistry);
        this.retriedCounter = Counter.builder("notification.delivery.retried")
                .description("Delivery attempts that scheduled a retry").register(meterRegistry);
        this.duplicateCounter = Counter.builder("notification.delivery.duplicate")
                .description("Duplicate events detected and ignored — expected under at-least-once")
                .register(meterRegistry);
        this.deliveryTimer = Timer.builder("notification.delivery.duration")
                .description("Provider call duration").register(meterRegistry);
    }

    /**
     * Processes one notification end to end.
     *
     * @return what happened, so the caller (the Kafka consumer) can decide
     *         whether to schedule a retry event
     */
    public ProcessingOutcome process(UUID notificationId) {
        MDC.put(MDC_NOTIFICATION_ID, notificationId.toString());
        try {
            ClaimedWork work;
            try {
                work = claim(notificationId);
            } catch (InvalidStateTransitionException e) {
                // Not an error. Either a duplicate event, or another consumer
                // got there first. Both are normal under at-least-once delivery.
                duplicateCounter.increment();
                log.debug("Skipping notification {}: {}", notificationId, e.getMessage());
                return ProcessingOutcome.ALREADY_HANDLED;
            } catch (ObjectOptimisticLockingFailureException e) {
                // Two consumers claimed simultaneously; this one lost. The
                // winner is processing it.
                duplicateCounter.increment();
                log.debug("Lost the claim race for notification {}", notificationId);
                return ProcessingOutcome.ALREADY_HANDLED;
            } catch (NotificationMissingException e) {
                // The event references a notification that does not exist. That
                // is a pipeline fault, not a delivery failure — the consumer
                // dead-letters it.
                log.error("Notification {} referenced by an event does not exist", notificationId);
                return ProcessingOutcome.NOT_FOUND;
            }

            NotificationProvider.DeliveryResult result = deliver(work);
            return record(work, result);

        } finally {
            MDC.remove(MDC_NOTIFICATION_ID);
        }
    }

    /**
     * Transaction 1: claim the notification.
     *
     * <p>{@code REQUIRES_NEW} so it commits independently and becomes visible
     * to other consumers immediately. Returns a snapshot of what the provider
     * needs, so the delivery step touches no managed entity and cannot
     * accidentally trigger a lazy load outside a transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimedWork claim(UUID notificationId) {
        Notification notification = notificationRepository.findByIdUnscoped(notificationId)
                .orElseThrow(() -> new NotificationMissingException(notificationId));

        int attemptNumber = notification.beginProcessing(clock.instant());
        notificationRepository.save(notification);

        return new ClaimedWork(
                notification.getId(),
                notification.getChannel(),
                notification.getRecipient(),
                notification.getSubject(),
                notification.getBody(),
                notification.getMetadata(),
                attemptNumber,
                notification.getMaxAttempts());
    }

    /**
     * Transaction-free: call the provider.
     *
     * <p>An adapter that throws despite the contract is caught and treated as a
     * transient failure. A misbehaving adapter must not take the pipeline down
     * or lose the notification; treating it as retryable is the forgiving
     * reading, and the attempt row records what happened.
     */
    private NotificationProvider.DeliveryResult deliver(ClaimedWork work) {
        var request = new NotificationProvider.DeliveryRequest(
                work.notificationId(), work.channel(), work.recipient(),
                work.subject(), work.body(), work.attemptNumber(), work.metadata());

        Instant start = clock.instant();
        try {
            return deliveryTimer.recordCallable(
                    () -> providers.forChannel(work.channel()).send(request));
        } catch (Exception e) {
            log.error("Provider for channel {} threw instead of returning a result; "
                            + "treating as a transient failure", work.channel(), e);
            return NotificationProvider.DeliveryResult.transientFailure(
                    "Provider adapter error: " + e.getClass().getSimpleName());
        } finally {
            work.setDurationMs(Duration.between(start, clock.instant()).toMillis());
        }
    }

    /**
     * Transaction 3: write the attempt row and the final status.
     *
     * <p>Both in one transaction, so it is impossible to end up with an attempt
     * recorded but the status unchanged, or the reverse.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessingOutcome record(ClaimedWork work, NotificationProvider.DeliveryResult result) {
        Instant now = clock.instant();

        Notification notification = notificationRepository.findByIdUnscoped(work.notificationId())
                .orElseThrow(() -> new NotificationMissingException(work.notificationId()));

        try {
            attemptRepository.saveAndFlush(buildAttempt(work, result, now));
        } catch (DataIntegrityViolationException duplicate) {
            // uq_attempts_notification_number fired: this attempt number is
            // already recorded, so another consumer handled it. Stop here
            // rather than overwriting its outcome.
            duplicateCounter.increment();
            log.warn("Attempt {} for notification {} already recorded; another consumer handled it",
                    work.attemptNumber(), work.notificationId());
            return ProcessingOutcome.ALREADY_HANDLED;
        }

        if (result.isSuccess()) {
            notification.markSent(now);
            notificationRepository.save(notification);
            sentCounter.increment();
            log.info("Delivered notification {} on attempt {} (provider ref {})",
                    work.notificationId(), work.attemptNumber(), result.providerReference());
            return ProcessingOutcome.SENT;
        }

        boolean retry = retryPolicy.shouldRetry(
                result.isRetryable(), work.attemptNumber(), work.maxAttempts());

        if (retry) {
            Duration delay = retryPolicy.nextDelay(work.attemptNumber() + 1);
            notification.scheduleRetry(now.plus(delay), result.errorMessage(), now);
            notificationRepository.save(notification);
            retriedCounter.increment();
            log.info("Notification {} failed on attempt {}/{}; retry in {}s — {}",
                    work.notificationId(), work.attemptNumber(), work.maxAttempts(),
                    delay.toSeconds(), result.errorMessage());
            return ProcessingOutcome.retryScheduled(delay);
        }

        // Either permanent, or the budget is spent. The message says which,
        // because "failed" alone sends an operator looking in the wrong place.
        String reason = result.isRetryable()
                ? "Retries exhausted after %d attempts: %s"
                        .formatted(work.attemptNumber(), result.errorMessage())
                : result.errorMessage();

        notification.markFailed(reason, now);
        notificationRepository.save(notification);
        failedCounter.increment();
        log.warn("Notification {} failed permanently after {} attempt(s): {}",
                work.notificationId(), work.attemptNumber(), reason);
        return ProcessingOutcome.FAILED;
    }

    private NotificationAttempt buildAttempt(ClaimedWork work,
                                             NotificationProvider.DeliveryResult result,
                                             Instant now) {
        long duration = work.durationMs();
        return switch (result.outcome()) {
            case SUCCESS -> NotificationAttempt.success(
                    work.notificationId(), work.attemptNumber(), result.providerReference(), duration, now);
            case TRANSIENT_FAILURE -> NotificationAttempt.transientFailure(
                    work.notificationId(), work.attemptNumber(), result.errorMessage(), duration, now);
            case PERMANENT_FAILURE -> NotificationAttempt.permanentFailure(
                    work.notificationId(), work.attemptNumber(), result.errorMessage(), duration, now);
        };
    }

    /**
     * A snapshot of what the provider needs, detached from the persistence
     * context.
     *
     * <p>A class rather than a record only because {@code durationMs} is
     * measured during delivery and written back. Everything else is final.
     */
    public static final class ClaimedWork {
        private final UUID notificationId;
        private final com.bhavesh.notification.notification.domain.NotificationChannel channel;
        private final String recipient;
        private final String subject;
        private final String body;
        private final String metadata;
        private final int attemptNumber;
        private final int maxAttempts;
        private long durationMs;

        public ClaimedWork(UUID notificationId,
                           com.bhavesh.notification.notification.domain.NotificationChannel channel,
                           String recipient, String subject, String body, String metadata,
                           int attemptNumber, int maxAttempts) {
            this.notificationId = notificationId;
            this.channel = channel;
            this.recipient = recipient;
            this.subject = subject;
            this.body = body;
            this.metadata = metadata;
            this.attemptNumber = attemptNumber;
            this.maxAttempts = maxAttempts;
        }

        public UUID notificationId() {
            return notificationId;
        }

        public com.bhavesh.notification.notification.domain.NotificationChannel channel() {
            return channel;
        }

        public String recipient() {
            return recipient;
        }

        public String subject() {
            return subject;
        }

        public String body() {
            return body;
        }

        public String metadata() {
            return metadata;
        }

        public int attemptNumber() {
            return attemptNumber;
        }

        public int maxAttempts() {
            return maxAttempts;
        }

        public long durationMs() {
            return durationMs;
        }

        void setDurationMs(long durationMs) {
            this.durationMs = durationMs;
        }

        /** Never includes the body, the subject or the unmasked recipient. */
        @Override
        public String toString() {
            return "ClaimedWork{notificationId=%s, channel=%s, attempt=%d/%d}"
                    .formatted(notificationId, channel, attemptNumber, maxAttempts);
        }
    }

    /** The event referenced a notification that is not in the database. */
    public static class NotificationMissingException extends RuntimeException {
        public NotificationMissingException(UUID id) {
            super("Notification " + id + " does not exist");
        }
    }
}
