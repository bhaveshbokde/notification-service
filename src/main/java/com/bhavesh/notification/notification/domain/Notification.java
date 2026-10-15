package com.bhavesh.notification.notification.domain;

import com.bhavesh.notification.common.domain.Ids;
import com.bhavesh.notification.common.exception.ErrorCode;
import com.bhavesh.notification.common.exception.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A delivery request, and the authoritative record of what happened to it.
 *
 * <p>Maps to {@code notifications} (V2, extended by V4).
 *
 * <h2>The row is the truth, the Kafka event is only a trigger</h2>
 * The consumer deliberately re-reads this row rather than acting on the event
 * payload. The event may be a duplicate, it may be stale, and by the time it is
 * consumed the notification may already be terminal — all three happen in
 * normal operation. Reading the row makes the consumer's decision a function of
 * current state rather than of a message that was true at some point in the
 * past.
 *
 * <h2>Transitions are methods, not setters</h2>
 * There is no {@code setStatus}. Every change goes through a method that
 * consults {@link NotificationStatus#canTransitionTo}, so an illegal jump such
 * as {@code SENT → PROCESSING} is impossible to express rather than merely
 * discouraged by a service that might be bypassed.
 *
 * <h2>Optimistic locking is load-bearing here</h2>
 * {@code @Version} is not decoration. Two consumers handling a duplicate Kafka
 * event can both load this row; the version check makes the second write fail
 * rather than silently overwrite the first one's outcome. That failure is
 * caught and treated as "someone else already handled it", which is the correct
 * interpretation.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    /**
     * Assigned by the application before insert — see {@link Ids} for why.
     * No {@code @GeneratedValue}: the outbox event written in the same
     * transaction needs this value, and reading back a generated key would cost
     * a round trip and rule out batching.
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private NotificationChannel channel;

    /**
     * {@code EnumType.STRING}, never {@code ORDINAL}. An ordinal mapping stores
     * the enum's position, so inserting a constant in the middle of
     * {@link NotificationStatus} would silently re-label every existing row —
     * here, turning failures into successes.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private NotificationPriority priority = NotificationPriority.NORMAL;

    @Column(name = "recipient", nullable = false, length = 320, updatable = false)
    private String recipient;

    @Column(name = "subject", length = 255, updatable = false)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text", updatable = false)
    private String body;

    /**
     * Caller metadata, stored as {@code jsonb}.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} is required: without it Hibernate
     * binds a String as {@code varchar} and PostgreSQL refuses the implicit
     * cast to {@code jsonb} with "column is of type jsonb but expression is of
     * type character varying".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts;

    /** Sanitised and truncated before it ever reaches this field. */
    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** First moved out of PENDING. Distinguishes "never picked up" from "working". */
    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    /** Reached a terminal state. {@code completedAt - createdAt} is end-to-end latency. */
    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** JPA requires a no-arg constructor; it is not meant for application code. */
    protected Notification() {
    }

    private Notification(UUID id, NotificationChannel channel, String recipient, String subject,
                         String body, String metadata, Long createdBy,
                         NotificationPriority priority, int maxAttempts, Instant now) {
        this.id = id;
        this.channel = channel;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.metadata = metadata;
        this.createdBy = createdBy;
        this.priority = priority == null ? NotificationPriority.NORMAL : priority;
        this.maxAttempts = maxAttempts;
        this.status = NotificationStatus.PENDING;
        this.attemptCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * The only way to create one.
     *
     * <p>Generates the id itself rather than accepting one, so a caller cannot
     * supply a value and two notifications cannot collide by accident.
     *
     * <p>The id is derived from the supplied {@code now}, not from
     * {@code System.currentTimeMillis()}. That keeps the timestamp embedded in
     * the id consistent with {@code createdAt} — otherwise the two could
     * disagree, which would make the id's ordering property quietly wrong
     * whenever the application clock is not the wall clock. It also means a
     * test with a fixed clock gets deterministic, orderable ids.
     */
    public static Notification create(NotificationChannel channel, String recipient, String subject,
                                      String body, String metadata, Long createdBy,
                                      NotificationPriority priority, int maxAttempts, Instant now) {
        return new Notification(Ids.timeOrdered(now), channel, recipient, subject, body,
                metadata, createdBy, priority, maxAttempts, now);
    }

    // ------------------------------------------------------------ transitions --

    /**
     * Claims this notification for a delivery attempt.
     *
     * <p>Increments {@code attemptCount} and returns the new value, which
     * becomes the {@code attempt_number} of the attempt row. That number is
     * covered by a unique index, so a duplicate Kafka event that somehow gets
     * this far collides on the insert rather than quietly recording a second
     * "attempt 2".
     *
     * @throws InvalidStateTransitionException if the notification is not claimable —
     *         which is how a concurrent or duplicate claim is rejected
     */
    public int beginProcessing(Instant now) {
        if (!status.isClaimable()) {
            throw new InvalidStateTransitionException(
                    "Notification %s is %s and cannot be claimed for processing"
                            .formatted(id, status),
                    status, NotificationStatus.PROCESSING);
        }
        transitionTo(NotificationStatus.PROCESSING, now);
        if (processingStartedAt == null) {
            this.processingStartedAt = now;
        }
        this.nextAttemptAt = null;
        return ++this.attemptCount;
    }

    /** The provider accepted it. Terminal. */
    public void markSent(Instant now) {
        transitionTo(NotificationStatus.SENT, now);
        this.completedAt = now;
        this.lastError = null;
    }

    /**
     * A transient failure; another attempt is due at {@code nextAttemptAt}.
     *
     * @param reason already sanitised by the caller
     */
    public void scheduleRetry(Instant nextAttemptAt, String reason, Instant now) {
        transitionTo(NotificationStatus.RETRY_SCHEDULED, now);
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncate(reason);
    }

    /** A permanent failure, or retries exhausted. Terminal. */
    public void markFailed(String reason, Instant now) {
        transitionTo(NotificationStatus.FAILED, now);
        this.completedAt = now;
        this.nextAttemptAt = null;
        this.lastError = truncate(reason);
    }

    /** Could not be processed at all. Terminal; needs an operator. */
    public void markDeadLettered(String reason, Instant now) {
        transitionTo(NotificationStatus.DEAD_LETTERED, now);
        this.completedAt = now;
        this.nextAttemptAt = null;
        this.lastError = truncate(reason);
    }

    private void transitionTo(NotificationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(
                    "Cannot move notification %s from %s to %s; allowed: %s"
                            .formatted(id, status, target, status.allowedTransitions()),
                    status, target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    // --------------------------------------------------------------- queries --

    /** Whether another delivery attempt is permitted by the configured budget. */
    public boolean hasAttemptsRemaining() {
        return attemptCount < maxAttempts;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    /** True when this caller owns the notification, or is an administrator. */
    public boolean isVisibleTo(Long userId, boolean admin) {
        return admin || Objects.equals(createdBy, userId);
    }

    /**
     * Truncates to the column width rather than letting it overflow.
     *
     * <p>A provider's error message must never be the thing that fails the
     * write recording why the provider failed.
     */
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

    public NotificationChannel getChannel() {
        return channel;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public NotificationPriority getPriority() {
        return priority;
    }

    public String getRecipient() {
        return recipient;
    }

    /** Masked for logging. Never log {@link #getRecipient()} directly. */
    public String getMaskedRecipient() {
        return channel.mask(recipient);
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public String getMetadata() {
        return metadata;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Notification that)) {
            return false;
        }
        // The id is assigned at construction rather than by the database, so
        // unlike a sequence-backed entity it is safe to compare directly.
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(Notification.class);
    }

    /**
     * Never includes the body, the subject or the unmasked recipient.
     *
     * <p>Notification content is the most sensitive thing this service holds —
     * password reset links, one-time codes, order details — and {@code toString}
     * ends up in exception messages and log lines by accident constantly.
     */
    @Override
    public String toString() {
        return "Notification{id=%s, channel=%s, status=%s, recipient=%s, attempts=%d/%d}"
                .formatted(id, channel, status, getMaskedRecipient(), attemptCount, maxAttempts);
    }
}
