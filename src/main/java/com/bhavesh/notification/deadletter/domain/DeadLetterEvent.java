package com.bhavesh.notification.deadletter.domain;

import com.bhavesh.notification.common.domain.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A Kafka message that could not be processed at all.
 *
 * <p>Maps to {@code dead_letter_events} (V4).
 *
 * <h2>This is not the same as a failed delivery</h2>
 * A notification in {@code FAILED} status means the pipeline worked and the
 * provider said no — there is a full attempt history explaining why, and
 * nothing is broken. A dead letter means the pipeline itself could not cope:
 * unparseable JSON, an event referencing a notification that does not exist, a
 * bug in the consumer, a schema change nobody coordinated.
 *
 * <p>Conflating the two is how real incidents get missed. A spike in FAILED
 * notifications is a provider problem; a spike in dead letters is <em>our</em>
 * problem, and it needs a different alert and a different response. They
 * therefore live in different tables with different queries.
 *
 * <h2>Why mirror the Kafka DLT into a database table</h2>
 * The Kafka dead-letter topic is the transport-level mechanism and it works,
 * but inspecting it needs broker access, a console consumer and someone who
 * knows the offsets. Mirroring into a table means an operator can see what is
 * waiting, read the failure reason and trigger a replay through the API —
 * which is what "safe dead-letter inspection" actually requires in practice.
 */
@Entity
@Table(name = "dead_letter_events")
public class DeadLetterEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_topic", nullable = false, length = 128, updatable = false)
    private String sourceTopic;

    /**
     * Partition and offset pin down exactly which record this was, so a replay
     * can be targeted and an operator can find it in the broker if they need to.
     * Nullable because a failure during deserialisation may not expose them.
     */
    @Column(name = "source_partition", updatable = false)
    private Integer sourcePartition;

    @Column(name = "source_offset", updatable = false)
    private Long sourceOffset;

    @Column(name = "message_key", length = 128, updatable = false)
    private String messageKey;

    /**
     * Stored as text, not {@code jsonb}.
     *
     * <p>A message arriving as invalid JSON is precisely the case this table
     * exists for, and a {@code jsonb} column would reject it on insert — losing
     * the evidence at the exact moment it matters most.
     */
    @Column(name = "payload", nullable = false, columnDefinition = "text", updatable = false)
    private String payload;

    /** Sanitised and truncated, like every other error column in this schema. */
    @Column(name = "failure_reason", nullable = false, length = 500, updatable = false)
    private String failureReason;

    @Column(name = "exception_type", length = 255, updatable = false)
    private String exceptionType;

    /** Populated when the payload was parseable enough to identify a target. */
    @Column(name = "notification_id", updatable = false)
    private UUID notificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeadLetterStatus status = DeadLetterStatus.UNRESOLVED;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    @Column(name = "replayed_by")
    private Long replayedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DeadLetterEvent() {
    }

    private DeadLetterEvent(UUID id, String sourceTopic, Integer sourcePartition, Long sourceOffset,
                            String messageKey, String payload, String failureReason,
                            String exceptionType, UUID notificationId, Instant now) {
        this.id = id;
        this.sourceTopic = sourceTopic;
        this.sourcePartition = sourcePartition;
        this.sourceOffset = sourceOffset;
        this.messageKey = messageKey;
        this.payload = payload;
        this.failureReason = truncate(failureReason);
        this.exceptionType = exceptionType;
        this.notificationId = notificationId;
        this.status = DeadLetterStatus.UNRESOLVED;
        this.createdAt = now;
    }

    public static DeadLetterEvent of(String sourceTopic, Integer partition, Long offset,
                                     String messageKey, String payload, String failureReason,
                                     String exceptionType, UUID notificationId, Instant now) {
        return new DeadLetterEvent(Ids.timeOrdered(), sourceTopic, partition, offset,
                messageKey, payload, failureReason, exceptionType, notificationId, now);
    }

    /** An administrator re-submitted this event for processing. */
    public void markReplayed(Long adminUserId, Instant now) {
        requireUnresolved("replayed");
        this.status = DeadLetterStatus.REPLAYED;
        this.replayedAt = now;
        this.replayedBy = adminUserId;
    }

    /** An administrator judged this unrecoverable and closed it. */
    public void markDiscarded(Long adminUserId, Instant now) {
        requireUnresolved("discarded");
        this.status = DeadLetterStatus.DISCARDED;
        this.replayedAt = now;
        this.replayedBy = adminUserId;
    }

    /**
     * Guards against replaying the same event twice.
     *
     * <p>Without this, two operators looking at the same backlog — or one
     * operator double-clicking — would each enqueue the notification again.
     */
    private void requireUnresolved(String action) {
        if (status != DeadLetterStatus.UNRESOLVED) {
            throw new IllegalStateException(
                    "Dead letter %s is already %s and cannot be %s".formatted(id, status, action));
        }
    }

    public boolean isReplayable() {
        return status == DeadLetterStatus.UNRESOLVED && notificationId != null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }

    public UUID getId() {
        return id;
    }

    public String getSourceTopic() {
        return sourceTopic;
    }

    public Integer getSourcePartition() {
        return sourcePartition;
    }

    public Long getSourceOffset() {
        return sourceOffset;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public DeadLetterStatus getStatus() {
        return status;
    }

    public Instant getReplayedAt() {
        return replayedAt;
    }

    public Long getReplayedBy() {
        return replayedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DeadLetterEvent that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(DeadLetterEvent.class);
    }

    /** Omits the payload, which is an unprocessed message and may contain anything. */
    @Override
    public String toString() {
        return "DeadLetterEvent{id=%s, topic=%s, partition=%s, offset=%s, status=%s}"
                .formatted(id, sourceTopic, sourcePartition, sourceOffset, status);
    }
}
