package com.bhavesh.notification.messaging;

import com.bhavesh.notification.deadletter.service.DeadLetterService;
import com.bhavesh.notification.delivery.DeliveryProcessor;
import com.bhavesh.notification.delivery.ProcessingOutcome;
import com.bhavesh.notification.notification.service.NotificationService.NotificationRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes notification events and drives delivery.
 *
 * <h2>The event is a trigger, not a source of truth</h2>
 * The listener extracts only the notification id from the payload and then
 * re-reads the row. That is deliberate: the event may be a duplicate, it may
 * have been published minutes ago, and by the time it arrives the notification
 * may already be terminal. Acting on the payload would make the decision a
 * function of a message that was true at some point in the past; reading the
 * row makes it a function of current state.
 *
 * <p>It is also why the payload carries no recipient and no body — a Kafka
 * topic is replicated and retained for days, which is a poor place for
 * one-time codes.
 *
 * <h2>Acknowledgement policy</h2>
 * The container commits the offset after this method returns normally
 * ({@code ack-mode: record}, auto-commit disabled). So:
 *
 * <ul>
 *   <li><strong>Return normally</strong> — the message is done with, whatever
 *       the delivery outcome was. A failed delivery is already recorded in the
 *       database and the retry is driven by a <em>new</em> event, not by
 *       redelivering this one.</li>
 *   <li><strong>Throw</strong> — the error handler retries and eventually
 *       routes to the dead-letter topic. Reserved for faults in the
 *       <em>pipeline</em>: an unparseable payload, a database that is down.</li>
 * </ul>
 *
 * <p>That distinction is the one that matters most here. If a declined SMS
 * threw, the consumer would redeliver the same message, the delivery would be
 * declined again, and the Kafka retry machinery would fight the application's
 * own retry policy — two competing mechanisms processing the same failure,
 * which is exactly what the brief warns against.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
    private static final String MDC_NOTIFICATION_ID = "notificationId";

    private final DeliveryProcessor deliveryProcessor;
    private final RetryEventPublisher retryPublisher;
    private final DeadLetterService deadLetterService;
    private final ObjectMapper objectMapper;

    private final Counter consumedCounter;
    private final Counter malformedCounter;

    public NotificationConsumer(DeliveryProcessor deliveryProcessor,
                                RetryEventPublisher retryPublisher,
                                DeadLetterService deadLetterService,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        this.deliveryProcessor = deliveryProcessor;
        this.retryPublisher = retryPublisher;
        this.deadLetterService = deadLetterService;
        this.objectMapper = objectMapper;

        this.consumedCounter = Counter.builder("notification.kafka.consumed")
                .description("Notification events consumed").register(meterRegistry);
        this.malformedCounter = Counter.builder("notification.kafka.malformed")
                .description("Events that could not be parsed — a pipeline fault, not a delivery failure")
                .register(meterRegistry);
    }

    /**
     * Main listener. Also subscribes to the retry topic, because the handling
     * is identical — a retry event is just another trigger to attempt delivery,
     * and duplicating the listener would mean duplicating the dead-letter and
     * metric logic too.
     *
     * <p>Topics come from {@code ${...}} property placeholders rather than
     * {@code #{@kafkaProperties.topic()}} SpEL. A {@code @ConfigurationProperties}
     * record is registered under a qualified bean name — here
     * {@code notification.kafka-com.bhavesh.notification.config.KafkaProperties}
     * — not a simple one, so the bean reference fails at startup with
     * "A component required a bean named 'kafkaProperties' that could not be
     * found". The placeholder reads the same values straight from the
     * environment and has no such ambiguity.
     */
    @KafkaListener(
            topics = {"${notification.kafka.topic}", "${notification.kafka.retry-topic}"},
            groupId = "${spring.kafka.consumer.group-id:notification-service}")
    public void onNotificationRequested(ConsumerRecord<String, String> record) {
        consumedCounter.increment();

        NotificationRequestedEvent event;
        try {
            event = objectMapper.readValue(record.value(), NotificationRequestedEvent.class);
        } catch (Exception e) {
            // A payload this consumer cannot parse. Retrying is pointless — it
            // will be just as unparseable next time — so it is dead-lettered
            // immediately rather than burning the retry budget.
            malformedCounter.increment();
            log.error("Unparseable event on {}-{}@{}: {}",
                    record.topic(), record.partition(), record.offset(), e.getMessage());
            deadLetterService.record(record, "Event payload could not be parsed: "
                    + e.getClass().getSimpleName(), e.getClass().getName(), null);
            return;
        }

        if (event.notificationId() == null) {
            malformedCounter.increment();
            log.error("Event on {}-{}@{} has no notificationId",
                    record.topic(), record.partition(), record.offset());
            deadLetterService.record(record, "Event is missing notificationId", null, null);
            return;
        }

        UUID notificationId = event.notificationId();
        MDC.put(MDC_NOTIFICATION_ID, notificationId.toString());
        try {
            handle(record, notificationId);
        } finally {
            // ThreadLocal, and the listener container pools threads. A value
            // left behind would mislabel the next message on this thread.
            MDC.remove(MDC_NOTIFICATION_ID);
        }
    }

    private void handle(ConsumerRecord<String, String> record, UUID notificationId) {
        ProcessingOutcome outcome = deliveryProcessor.process(notificationId);

        switch (outcome.kind()) {
            case SENT, FAILED -> log.debug("Notification {} finished: {}", notificationId, outcome.kind());

            case ALREADY_HANDLED ->
                // A duplicate, or another consumer won the race. Normal under
                // at-least-once delivery; acknowledge and move on.
                    log.debug("Notification {} was already handled; acknowledging duplicate",
                            notificationId);

            case RETRY_SCHEDULED ->
                // The notification is RETRY_SCHEDULED in the database. Publish a
                // NEW event onto the retry topic rather than letting this message
                // be redelivered — redelivery would happen immediately and ignore
                // the backoff entirely.
                    retryPublisher.scheduleRetry(notificationId, outcome.retryDelay());

            case NOT_FOUND -> {
                // The event references a row that does not exist. That is a
                // pipeline fault — a lost write, a manual deletion, a bug — and
                // no amount of retrying will fix it.
                log.error("Event references notification {} which does not exist; dead-lettering",
                        notificationId);
                deadLetterService.record(record,
                        "Event references a notification that does not exist",
                        null, notificationId);
            }
        }
    }
}
