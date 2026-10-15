package com.bhavesh.notification.outbox.domain;

import com.bhavesh.notification.common.domain.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An event waiting to be published to Kafka.
 *
 * <p>Maps to {@code outbox_events} (V3).
 *
 * <h2>The failure this exists to prevent</h2>
 * Saving the notification and then calling {@code kafkaTemplate.send()} looks
 * atomic and is not. The two are separate systems with no shared transaction,
 * so there is a window in which the notification commits and the publish does
 * not — the broker is down, the send is asynchronous and returns before
 * acknowledgement, or the process dies in between. The notification then sits
 * PENDING forever, and nothing in the system knows.
 *
 * <p>Writing this row in the <em>same transaction</em> as the notification
 * removes the window: both commit or neither does. A separate publisher then
 * drains the table, marking each row PUBLISHED only after the broker
 * acknowledges.
 *
 * <h2>The cost, stated plainly</h2>
 * Delivery becomes at-least-once, not exactly-once. If the publisher sends
 * successfully and dies before recording that fact, the event is sent again on
 * recovery. That is unavoidable without a distributed transaction, and it is
 * why the consumer must be idempotent — which the notification status check and
 * {@code uq_attempts_notification_number} together enforce.
 *
 * <h2>Leases rather than a single publisher</h2>
 * A row is claimed by stamping {@link #claimedAt} and {@link #claimedBy}. The
 * claim expires, so a publisher that dies mid-batch does not strand its rows
 * forever — the next publisher reclaims them once the lease is older than the
 * configured duration. The claim itself is taken with {@code FOR UPDATE SKIP
 * LOCKED}, which is what makes it safe for several publishers to run at once.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Deliberately generic ({@code aggregateType} + {@code aggregateId} rather
     * than a foreign key to notifications) so a second aggregate can use the
     * same outbox later without a schema change — and so the event survives the
     * deletion of whatever produced it.
     */
    @Column(name = "aggregate_type", nullable = false, length = 64, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 128, updatable = false)
    private String topic;

    /**
     * The Kafka message key. Set to the notification id, so every event for one
     * notification lands on the same partition and they keep their relative
     * order. Without this, a retry event could be consumed before the original.
     */
    @Column(name = "message_key", nullable = false, length = 128, updatable = false)
    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    /**
     * Which publisher instance holds the claim. Diagnostic only — the lease
     * expiry is what actually releases a stuck row, not this value — but when
     * events stop flowing it answers "which instance is wedged?" immediately.
     */
    @Column(name = "claimed_by", length = 64)
    private String claimedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    private OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType,
                        String topic, String messageKey, String payload, Instant now) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.createdAt = now;
    }

    public static OutboxEvent forNotification(UUID notificationId, String eventType,
                                              String topic, String payload, Instant now) {
        return new OutboxEvent(Ids.timeOrdered(), "Notification", notificationId,
                eventType, topic, notificationId.toString(), payload, now);
    }

    // ------------------------------------------------------------ transitions --

    /** Marks this row as being published by {@code publisherId}. */
    public void claim(String publisherId, Instant now) {
        this.status = OutboxStatus.PUBLISHING;
        this.claimedAt = now;
        this.claimedBy = publisherId;
    }

    /**
     * The broker acknowledged. Terminal.
     *
     * <p>Only called after the producer future completes successfully. Calling
     * it on {@code send()} returning would defeat the entire mechanism: the
     * send is asynchronous, so the call returns long before the record is
     * durable.
     */
    public void markPublished(Instant now) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.claimedAt = null;
        this.claimedBy = null;
        this.lastError = null;
    }

    /**
     * The publish failed. Returns to PENDING so it is retried, unless the
     * attempt budget is exhausted — in which case it becomes FAILED and needs
     * an operator, because something is wrong that retrying will not fix.
     */
    public void markPublishFailed(String error, int maxAttempts, Instant now) {
        this.attemptCount++;
        this.lastError = truncate(error);
        this.claimedAt = null;
        this.claimedBy = null;
        this.status = attemptCount >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING;
    }

    /**
     * Whether a publisher's claim on this row has expired.
     *
     * <p>A claim outliving its lease means the publisher that took it died, so
     * another may safely take over. The lease must be longer than the worst-case
     * time to publish one batch, or two publishers will fight over healthy rows.
     */
    public boolean isLeaseExpired(Duration leaseDuration, Instant now) {
        return status == OutboxStatus.PUBLISHING
                && claimedAt != null
                && claimedAt.plus(leaseDuration).isBefore(now);
    }

    public boolean isPublished() {
        return status == OutboxStatus.PUBLISHED;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }

    // --------------------------------------------------------------- getters --

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OutboxEvent that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(OutboxEvent.class);
    }

    /** Omits the payload, which contains the notification body and recipient. */
    @Override
    public String toString() {
        return "OutboxEvent{id=%s, type=%s, aggregateId=%s, status=%s, attempts=%d}"
                .formatted(id, eventType, aggregateId, status, attemptCount);
    }
}
