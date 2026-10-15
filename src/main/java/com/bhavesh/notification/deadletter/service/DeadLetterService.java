package com.bhavesh.notification.deadletter.service;

import com.bhavesh.notification.common.dto.PageResponse;
import com.bhavesh.notification.common.exception.ApiException;
import com.bhavesh.notification.common.exception.ErrorCode;
import com.bhavesh.notification.common.exception.ResourceNotFoundException;
import com.bhavesh.notification.config.KafkaProperties;
import com.bhavesh.notification.deadletter.domain.DeadLetterEvent;
import com.bhavesh.notification.deadletter.domain.DeadLetterStatus;
import com.bhavesh.notification.deadletter.dto.DeadLetterResponse;
import com.bhavesh.notification.deadletter.repository.DeadLetterEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Records, inspects and replays messages the pipeline could not process.
 *
 * <h2>What belongs here, and what does not</h2>
 * A dead letter is a <em>processing</em> failure: an unparseable payload, an
 * event referencing a notification that does not exist, a bug in the consumer.
 * A notification that a provider declined is <strong>not</strong> a dead
 * letter — it is a FAILED notification with a full attempt history, and the
 * pipeline worked exactly as intended.
 *
 * <p>Keeping them apart matters operationally: a rising FAILED count means a
 * provider is having a bad day, while a rising dead-letter count means
 * <em>this service</em> is broken. They need different alerts and different
 * responses, and a single "errors" metric would hide the second inside the
 * first.
 *
 * <h2>Why mirror the Kafka DLT into a table</h2>
 * The Kafka dead-letter topic is the transport-level mechanism and it works,
 * but inspecting it needs broker access, a console consumer and someone who
 * knows what offset to look at. A table means an operator can list what is
 * waiting, read the failure reason and trigger a replay through the API.
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    /**
     * Payloads are truncated before storage. A dead letter is by definition an
     * untrusted message, and an attacker who can reach the producer should not
     * be able to fill the database by sending one enormous record.
     */
    private static final int MAX_STORED_PAYLOAD = 8_000;

    private final DeadLetterEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DeadLetterService(DeadLetterEventRepository repository,
                             KafkaTemplate<String, String> kafkaTemplate,
                             KafkaProperties kafkaProperties,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry,
                             Clock clock) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaProperties = kafkaProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;

        // The gauge to alert on. Unlike a failed notification, a non-zero value
        // here means something is wrong with this service rather than with a
        // provider.
        meterRegistry.gauge("notification.deadletter.unresolved", repository,
                r -> r.countByStatus(DeadLetterStatus.UNRESOLVED));
    }

    /**
     * Records a message that could not be processed, and forwards it to the
     * Kafka dead-letter topic.
     *
     * <p>{@code REQUIRES_NEW} so the record survives even when the caller's
     * transaction is rolling back — which is the usual situation, since
     * something has just gone wrong. Losing the evidence of a failure because
     * the failing transaction rolled back would be a particularly unhelpful
     * design.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ConsumerRecord<String, String> record, String failureReason,
                       String exceptionType, UUID notificationId) {

        DeadLetterEvent event = DeadLetterEvent.of(
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                truncatePayload(record.value()),
                failureReason,
                exceptionType,
                notificationId,
                clock.instant());

        repository.save(event);

        log.error("DEAD LETTER recorded: id={} source={}-{}@{} reason={}",
                event.getId(), record.topic(), record.partition(), record.offset(), failureReason);

        forwardToDeadLetterTopic(record, failureReason);
    }

    /**
     * Forwards to the Kafka DLT as well as the table.
     *
     * <p>Both, not either: the table is for humans, the topic is for any
     * downstream tooling that already watches dead-letter topics. A failure to
     * forward is logged but does not throw — the durable record is the table
     * row, which is already committed, and failing here would lose it.
     */
    private void forwardToDeadLetterTopic(ConsumerRecord<String, String> record, String reason) {
        try {
            kafkaTemplate.send(kafkaProperties.dltTopic(),
                    record.key() == null ? "unknown" : record.key(),
                    record.value());
        } catch (Exception e) {
            log.warn("Could not forward to the dead-letter topic; the database record stands: {}",
                    e.getMessage());
        }
    }

    private String truncatePayload(String payload) {
        if (payload == null) {
            return "(null payload)";
        }
        return payload.length() <= MAX_STORED_PAYLOAD
                ? payload
                : payload.substring(0, MAX_STORED_PAYLOAD) + "...[truncated]";
    }

    // ------------------------------------------------------------- inspection --

    @Transactional(readOnly = true)
    public PageResponse<DeadLetterResponse> list(DeadLetterStatus status, Pageable pageable) {
        var page = status == null
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findByStatusOrderByCreatedAtDesc(status, pageable);
        return PageResponse.from(page, DeadLetterResponse::from);
    }

    @Transactional(readOnly = true)
    public DeadLetterResponse get(UUID id) {
        return repository.findById(id)
                .map(DeadLetterResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Dead letter event", id));
    }

    public long unresolvedCount() {
        return repository.countByStatus(DeadLetterStatus.UNRESOLVED);
    }

    // ---------------------------------------------------------------- replay --

    /**
     * Re-publishes a dead-lettered event for processing.
     *
     * <p>The entity's own {@code markReplayed} refuses if the event has already
     * been resolved, which is what stops two operators looking at the same
     * backlog — or one operator double-clicking — from enqueuing the
     * notification twice.
     *
     * <p>Only events that identified a notification can be replayed. An
     * unparseable payload has nothing to replay <em>to</em>; the right action
     * there is to fix the producer and discard the record.
     */
    @Transactional
    public DeadLetterResponse replay(UUID id, Long adminUserId) {
        DeadLetterEvent event = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dead letter event", id));

        if (!event.isReplayable()) {
            throw new ApiException(ErrorCode.NOTIFICATION_NOT_REPLAYABLE,
                    event.getNotificationId() == null
                            ? "This event could not be associated with a notification, so there is "
                              + "nothing to replay. Fix the producer and discard it."
                            : "This event is already %s".formatted(event.getStatus()));
        }

        event.markReplayed(adminUserId, clock.instant());
        repository.save(event);

        // Publish onto the main topic, not the retry topic: a replay is a fresh
        // processing request, and the notification's own retry budget is
        // unchanged.
        kafkaTemplate.send(kafkaProperties.topic(),
                event.getNotificationId().toString(),
                buildReplayPayload(event.getNotificationId()));

        log.warn("Dead letter {} replayed by user {} for notification {}",
                id, adminUserId, event.getNotificationId());

        return DeadLetterResponse.from(event);
    }

    /** Closes an event an operator has judged unrecoverable. */
    @Transactional
    public DeadLetterResponse discard(UUID id, Long adminUserId) {
        DeadLetterEvent event = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dead letter event", id));

        if (event.getStatus() != DeadLetterStatus.UNRESOLVED) {
            throw new ApiException(ErrorCode.NOTIFICATION_NOT_REPLAYABLE,
                    "This event is already %s".formatted(event.getStatus()));
        }

        event.markDiscarded(adminUserId, clock.instant());
        repository.save(event);

        log.warn("Dead letter {} discarded by user {}", id, adminUserId);
        return DeadLetterResponse.from(event);
    }

    private String buildReplayPayload(UUID notificationId) {
        try {
            return objectMapper.writeValueAsString(
                    new com.bhavesh.notification.notification.service.NotificationService
                            .NotificationRequestedEvent(notificationId, null, null, clock.instant()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the replay payload", e);
        }
    }
}
