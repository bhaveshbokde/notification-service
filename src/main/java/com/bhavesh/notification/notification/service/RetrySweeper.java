package com.bhavesh.notification.notification.service;

import com.bhavesh.notification.messaging.RetryEventPublisher;
import com.bhavesh.notification.notification.domain.Notification;
import com.bhavesh.notification.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Recovers notifications whose retry event was lost.
 *
 * <h2>Why this exists</h2>
 * {@link RetryEventPublisher} schedules the retry republication in memory. That
 * is accurate and simple, and it has one failure mode: if the instance dies
 * during the delay, the scheduled task dies with it and no retry event is ever
 * published. The notification would sit in {@code RETRY_SCHEDULED} forever —
 * not failed, not delivered, just quietly stuck.
 *
 * <p>The database already knows better: the row is {@code RETRY_SCHEDULED} with
 * a committed {@code next_attempt_at}. This sweeper reads that and republishes
 * anything whose retry became due while no event existed. It turns a lost
 * schedule from "a notification is lost" into "a notification is late".
 *
 * <p>That is the whole reason the in-memory schedule is an acceptable choice.
 * Without this backstop it would not be.
 *
 * <h2>Why it does not double-publish</h2>
 * It only picks up notifications whose {@code next_attempt_at} has <em>already
 * passed</em> by a grace period. A retry scheduled normally fires on time and
 * moves the notification to PROCESSING long before the grace period elapses, so
 * the sweeper never sees it. Only genuinely orphaned rows linger.
 *
 * <p>And if it did double-publish, the duplicate would be absorbed: the second
 * event finds the notification already PROCESSING and
 * {@link com.bhavesh.notification.delivery.DeliveryProcessor} treats it as
 * ALREADY_HANDLED.
 */
@Component
public class RetrySweeper {

    private static final Logger log = LoggerFactory.getLogger(RetrySweeper.class);

    /**
     * How long past its due time a retry must be before the sweeper claims it.
     *
     * <p>Generous on purpose. A healthy in-memory schedule fires within
     * milliseconds of the due time; anything still waiting a minute later is
     * genuinely orphaned. Shortening this would start racing the normal path
     * for no benefit.
     */
    private static final Duration GRACE_PERIOD = Duration.ofMinutes(1);

    /** Bounded, so one sweep cannot flood the retry topic after a long outage. */
    private static final int BATCH_SIZE = 100;

    private final NotificationRepository repository;
    private final RetryEventPublisher retryPublisher;
    private final Clock clock;
    private final Counter sweptCounter;

    public RetrySweeper(NotificationRepository repository,
                        RetryEventPublisher retryPublisher,
                        MeterRegistry meterRegistry,
                        Clock clock) {
        this.repository = repository;
        this.retryPublisher = retryPublisher;
        this.clock = clock;
        this.sweptCounter = Counter.builder("notification.retry.swept")
                .description("Overdue retries recovered by the sweeper — non-zero means a "
                        + "scheduled retry was lost, usually to an instance restart")
                .register(meterRegistry);

        // Stuck in PROCESSING: a consumer died mid-delivery. Nothing else will
        // move these, because PROCESSING is not claimable — so they need a
        // human, and the gauge is how anyone finds out.
        meterRegistry.gauge("notification.stuck.processing", repository,
                r -> r.countStuckInProcessing(Instant.now().minus(Duration.ofMinutes(15))));
    }

    @Scheduled(fixedDelayString = "${notification.retry.sweep-interval:PT30S}")
    @Transactional(readOnly = true)
    public void sweepOverdueRetries() {
        try {
            Instant cutoff = clock.instant().minus(GRACE_PERIOD);
            List<Notification> overdue =
                    repository.findDueForRetry(cutoff, PageRequest.of(0, BATCH_SIZE));

            if (overdue.isEmpty()) {
                return;
            }

            // WARN, not INFO: every row here represents a retry event that was
            // lost. It is recoverable, but it is not normal.
            log.warn("Retry sweeper found {} overdue notification(s); republishing", overdue.size());
            sweptCounter.increment(overdue.size());

            overdue.forEach(n -> retryPublisher.scheduleRetry(n.getId(), Duration.ZERO));

        } catch (Exception e) {
            // A scheduled method that throws may simply stop being rescheduled,
            // and a silent stop here would disable the safety net without
            // anyone noticing.
            log.error("Retry sweep failed; will retry on the next cycle", e);
        }
    }
}
