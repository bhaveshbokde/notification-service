package com.bhavesh.notification.outbox.service;

import com.bhavesh.notification.config.OutboxProperties;
import com.bhavesh.notification.outbox.domain.OutboxEvent;
import com.bhavesh.notification.outbox.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Drains the outbox into Kafka.
 *
 * <h2>How one cycle works</h2>
 * <ol>
 *   <li><strong>Recover</strong> — return rows whose publisher lease expired to
 *       PENDING, so a publisher that died mid-batch strands nothing.</li>
 *   <li><strong>Claim</strong> — in a short transaction, select a batch with
 *       {@code FOR UPDATE SKIP LOCKED} and stamp each row PUBLISHING. The
 *       transaction commits immediately.</li>
 *   <li><strong>Publish</strong> — send each event and <em>block on the
 *       broker's acknowledgement</em>, outside any transaction.</li>
 *   <li><strong>Record</strong> — in a fresh short transaction per event, mark
 *       it PUBLISHED or record the failure.</li>
 * </ol>
 *
 * <h2>Why claiming and publishing are separate transactions</h2>
 * The network call to Kafka must not happen inside a database transaction.
 * Holding a connection open across someone else's network latency is how a
 * pool gets exhausted by a slow dependency, and the transaction would stay open
 * for the full {@code delivery.timeout.ms} if the broker were unreachable.
 *
 * <p>The cost of splitting them is the at-least-once window: if this process
 * dies after the broker acknowledges but before the PUBLISHED write commits,
 * the lease expires and the event is sent again. That is unavoidable without a
 * distributed transaction, and it is precisely why the consumer is idempotent.
 *
 * <h2>Why {@code get()} rather than a callback</h2>
 * {@code KafkaTemplate.send()} returns a future and returns <em>immediately</em>.
 * Marking the row PUBLISHED at that point would defeat the entire mechanism —
 * the record may never reach the broker. Blocking on the future is what makes
 * "PUBLISHED" mean "the broker has it".
 *
 * <h2>Why fixed delay rather than fixed rate</h2>
 * {@code fixedRate} would queue overlapping executions if a cycle ever took
 * longer than the interval, and two cycles running concurrently on one instance
 * would fight over the same rows. {@code fixedDelayString} guarantees the next
 * cycle starts only after this one finishes.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /**
     * How long to wait for a broker acknowledgement before giving up on one
     * event. Comfortably above the producer's own {@code delivery.timeout.ms}
     * (30s), so the producer's retry logic gets to finish rather than being
     * cut off by this timeout.
     */
    private static final long PUBLISH_TIMEOUT_SECONDS = 45;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * Identifies this publisher instance in {@code claimed_by}. Diagnostic
     * only — lease expiry is what actually releases a stuck row — but when
     * events stop flowing it answers "which instance is wedged?" immediately.
     */
    private final String publisherId;

    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Counter recoveredCounter;

    public OutboxPublisher(OutboxEventRepository repository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           OutboxProperties properties,
                           TransactionTemplate transactionTemplate,
                           MeterRegistry meterRegistry,
                           Clock clock) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.publisherId = buildPublisherId();

        this.publishedCounter = Counter.builder("notification.outbox.published")
                .description("Outbox events acknowledged by the broker")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("notification.outbox.publish.failed")
                .description("Outbox publish attempts that failed")
                .register(meterRegistry);
        this.recoveredCounter = Counter.builder("notification.outbox.leases.recovered")
                .description("Expired publisher leases reclaimed — non-zero means an instance died")
                .register(meterRegistry);

        // The backlog gauge is the earliest signal that the pipeline has
        // stalled: it rises long before anyone notices notifications are not
        // arriving. A gauge rather than a counter because it is a level, not a
        // rate.
        meterRegistry.gauge("notification.outbox.backlog", repository,
                OutboxEventRepository::countBacklog);
    }

    private static String buildPublisherId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        // Host plus a random suffix: two instances on one host must not share
        // an identifier, or the diagnostic value is lost.
        return (host + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * One publish cycle.
     *
     * <p>Deliberately <strong>not</strong> {@code @Transactional}. The method
     * opens several short transactions internally; wrapping the whole cycle in
     * one would hold a connection across every Kafka round trip in the batch.
     */
    @Scheduled(fixedDelayString = "${notification.outbox.poll-interval:PT1S}")
    public void publishPending() {
        try {
            recoverExpiredLeases();

            List<OutboxEvent> claimed = claimBatch();
            if (claimed.isEmpty()) {
                return;
            }

            log.debug("Publisher {} claimed {} outbox event(s)", publisherId, claimed.size());
            for (OutboxEvent event : claimed) {
                publishOne(event);
            }
        } catch (Exception e) {
            // A scheduled method that throws is simply not rescheduled by some
            // configurations, and a silent stop here means notifications stop
            // flowing with no error anywhere. Catching keeps the loop alive;
            // the next cycle retries whatever was left.
            log.error("Outbox publish cycle failed; will retry on the next poll", e);
        }
    }

    /**
     * Returns rows abandoned by a dead publisher to PENDING.
     *
     * <p>A non-zero result is worth noticing: it means an instance died
     * mid-batch, which is why it is counted rather than only logged.
     */
    private void recoverExpiredLeases() {
        Instant expiredBefore = clock.instant().minus(properties.leaseDuration());
        // Integer, not int: execute() returns null if the callback does, and
        // unboxing that into an int would throw a NullPointerException inside
        // the scheduled method — which would stop the publisher entirely.
        Integer recovered = transactionTemplate.execute(status ->
                repository.releaseExpiredClaims(expiredBefore));

        if (recovered != null && recovered > 0) {
            recoveredCounter.increment(recovered);
            log.warn("Reclaimed {} outbox event(s) from expired publisher leases "
                    + "— an instance likely died mid-batch", recovered);
        }
    }

    /**
     * Claims a batch in its own short transaction.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} inside the query means several
     * publishers can run this concurrently and each gets a disjoint batch. The
     * row lock lasts only until this transaction commits, which is why each row
     * is also stamped PUBLISHING — that stamp is what carries the claim beyond
     * the lock's lifetime.
     */
    private List<OutboxEvent> claimBatch() {
        return transactionTemplate.execute(status -> {
            List<OutboxEvent> events = repository.claimBatch(properties.batchSize());
            Instant now = clock.instant();
            events.forEach(e -> e.claim(publisherId, now));
            repository.saveAll(events);
            return events;
        });
    }

    /**
     * Publishes one event and records the outcome.
     *
     * <p>The {@code get()} is the important line: it blocks until the broker
     * acknowledges, so the subsequent PUBLISHED write is a statement of fact
     * rather than of intent.
     */
    private void publishOne(OutboxEvent event) {
        try {
            SendResult<String, String> result = kafkaTemplate
                    .send(event.getTopic(), event.getMessageKey(), event.getPayload())
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            markPublished(event.getId());
            publishedCounter.increment();

            log.debug("Published outbox event {} to {}-{}@{}",
                    event.getId(),
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());

        } catch (InterruptedException e) {
            // Restore the flag: swallowing it leaves the thread looking
            // un-interrupted to everything upstream, so a shutdown silently
            // fails to propagate. The event stays claimed and its lease expires.
            Thread.currentThread().interrupt();
            log.warn("Interrupted while publishing outbox event {}; it will be retried", event.getId());

        } catch (Exception e) {
            failedCounter.increment();
            markPublishFailed(event.getId(), describe(e));
            log.error("Failed to publish outbox event {} (attempt {}): {}",
                    event.getId(), event.getAttemptCount() + 1, describe(e));
        }
    }

    /**
     * {@code REQUIRES_NEW} so this commits independently of the cycle. One
     * event's bookkeeping must not be rolled back by a later event's failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID eventId) {
        repository.findById(eventId).ifPresent(e -> {
            e.markPublished(clock.instant());
            repository.save(e);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublishFailed(UUID eventId, String error) {
        repository.findById(eventId).ifPresent(e -> {
            e.markPublishFailed(error, properties.maxPublishAttempts(), clock.instant());
            repository.save(e);
        });
    }

    /**
     * A short, safe description of a failure.
     *
     * <p>The exception's own message can contain a broker address, a serialised
     * payload fragment or a stack trace; this keeps the type and the message
     * only, and the full trace goes to the log.
     */
    private String describe(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** Exposed for tests and diagnostics. */
    public String publisherId() {
        return publisherId;
    }
}
