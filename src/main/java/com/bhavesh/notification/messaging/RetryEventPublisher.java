package com.bhavesh.notification.messaging;

import com.bhavesh.notification.config.KafkaProperties;
import com.bhavesh.notification.notification.service.NotificationService.NotificationRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Republishes a notification onto the retry topic after its backoff elapses.
 *
 * <h2>Why a new event rather than redelivering the original</h2>
 * Throwing from the consumer would make Kafka redeliver the same message — but
 * redelivery is governed by the container's own backoff, not by the
 * notification's. Two competing retry mechanisms would be processing the same
 * failure, with the faster one winning and the configured exponential backoff
 * silently ignored.
 *
 * <p>Publishing a fresh event means one retry policy, expressed in one place,
 * and the delay is the one {@link com.bhavesh.notification.delivery.RetryPolicy}
 * computed.
 *
 * <h2>How the delay is implemented, and its honest limitation</h2>
 * Kafka has no native per-message delay. The options are:
 *
 * <ul>
 *   <li><strong>Scheduled in-process republish</strong> (what this does) —
 *       simple and accurate. The weakness is that the schedule lives in this
 *       JVM's memory: if the instance dies during the delay, the republish
 *       never happens.</li>
 *   <li><strong>Tiered retry topics</strong> (a 5s topic, a 30s topic, a 5m
 *       topic, each with a consumer that sleeps) — survives a restart, but
 *       needs a topic per tier and gives only coarse granularity.</li>
 *   <li><strong>An external scheduler</strong> — accurate and durable, and a
 *       whole additional component.</li>
 * </ul>
 *
 * <p>The in-memory schedule is chosen because the <strong>database is the
 * backstop</strong>: the notification is already {@code RETRY_SCHEDULED} with
 * {@code next_attempt_at} committed, and
 * {@link com.bhavesh.notification.notification.service.RetrySweeper} picks up
 * anything whose retry became due while no event existed. So a lost schedule
 * costs latency, not the notification. That trade-off is the reason the sweeper
 * exists at all, and it is documented rather than left as a surprise.
 */
@Component
public class RetryEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RetryEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TaskScheduler taskScheduler;
    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Counter scheduledCounter;

    public RetryEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               TaskScheduler taskScheduler,
                               KafkaProperties kafkaProperties,
                               ObjectMapper objectMapper,
                               MeterRegistry meterRegistry,
                               Clock clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.taskScheduler = taskScheduler;
        this.kafkaProperties = kafkaProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.scheduledCounter = Counter.builder("notification.retry.scheduled")
                .description("Retry events scheduled for republication")
                .register(meterRegistry);
    }

    /**
     * Schedules a retry event for {@code delay} from now.
     *
     * <p>Non-blocking: the listener thread must not sleep, or it would hold its
     * partition and stall every other notification behind it.
     */
    public void scheduleRetry(UUID notificationId, Duration delay) {
        scheduledCounter.increment();
        Instant fireAt = clock.instant().plus(delay == null ? Duration.ZERO : delay);

        log.debug("Scheduling retry for notification {} at {} ({}s)",
                notificationId, fireAt, delay == null ? 0 : delay.toSeconds());

        taskScheduler.schedule(() -> publishRetry(notificationId), fireAt);
    }

    private void publishRetry(UUID notificationId) {
        try {
            // A minimal event: the processor re-reads the row for everything
            // else, so the payload only needs to identify the target.
            String payload = objectMapper.writeValueAsString(new NotificationRequestedEvent(
                    notificationId, null, null, clock.instant()));

            kafkaTemplate.send(kafkaProperties.retryTopic(), notificationId.toString(), payload);

            log.debug("Published retry event for notification {}", notificationId);
        } catch (Exception e) {
            // The database still says RETRY_SCHEDULED with a due time, so the
            // sweeper will pick it up. Logged at WARN rather than ERROR
            // because it is recoverable by design.
            log.warn("Could not publish retry event for notification {}; "
                    + "the retry sweeper will recover it: {}", notificationId, e.getMessage());
        }
    }
}
