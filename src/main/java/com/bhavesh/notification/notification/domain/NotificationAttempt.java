package com.bhavesh.notification.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One provider call, and what came of it.
 *
 * <p>Maps to {@code notification_attempts} (V2). Append-only: a row is written
 * when an attempt completes and is never updated. That is the whole point — the
 * question "why did attempt 2 fail?" must still be answerable after attempt 3
 * has succeeded, and a mutable status field on the notification cannot answer
 * it.
 *
 * <h2>Why this is a separate table rather than columns on the notification</h2>
 * "What is the current state" and "what happened on each attempt" are different
 * questions with different lifetimes. Folding the second into the first means
 * each retry overwrites the evidence of the previous failure, which is exactly
 * the evidence you need when a notification eventually fails for good.
 *
 * <h2>The unique index is load-bearing</h2>
 * {@code uq_attempts_notification_number} on {@code (notification_id,
 * attempt_number)} means a duplicate Kafka event that somehow passes the status
 * check still cannot record a second "attempt 2" — the insert collides and the
 * consumer learns it is handling a replay. It is the last line of defence
 * behind the status transition and the optimistic lock.
 *
 * <p>Holds {@code notificationId} as a plain column rather than a
 * {@code @ManyToOne}. An attempt needs the identity of its notification, not
 * the notification itself, and mapping the association would make every write
 * either load the parent or risk a lazy-initialisation failure.
 */
@Entity
@Table(name = "notification_attempts")
public class NotificationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 24, updatable = false)
    private AttemptOutcome outcome;

    /** Whatever the provider called this delivery. Null when it never answered. */
    @Column(name = "provider_reference", length = 100, updatable = false)
    private String providerReference;

    /** Sanitised and truncated. Never a stack trace, never a credential. */
    @Column(name = "error_message", length = 500, updatable = false)
    private String errorMessage;

    /**
     * Provider call duration. The p99 of this is the first thing to look at
     * when the pipeline starts backing up — a provider that went from 50ms to
     * 5s will exhaust the consumer threads long before it starts erroring.
     */
    @Column(name = "duration_ms", updatable = false)
    private Integer durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationAttempt() {
    }

    private NotificationAttempt(UUID notificationId, int attemptNumber, AttemptOutcome outcome,
                                String providerReference, String errorMessage,
                                Integer durationMs, Instant createdAt) {
        this.notificationId = notificationId;
        this.attemptNumber = attemptNumber;
        this.outcome = outcome;
        this.providerReference = providerReference;
        this.errorMessage = truncate(errorMessage);
        this.durationMs = durationMs;
        this.createdAt = createdAt;
    }

    public static NotificationAttempt success(UUID notificationId, int attemptNumber,
                                              String providerReference, long durationMs,
                                              Instant now) {
        return new NotificationAttempt(notificationId, attemptNumber, AttemptOutcome.SUCCESS,
                providerReference, null, clampDuration(durationMs), now);
    }

    public static NotificationAttempt transientFailure(UUID notificationId, int attemptNumber,
                                                       String errorMessage, long durationMs,
                                                       Instant now) {
        return new NotificationAttempt(notificationId, attemptNumber, AttemptOutcome.TRANSIENT_FAILURE,
                null, errorMessage, clampDuration(durationMs), now);
    }

    public static NotificationAttempt permanentFailure(UUID notificationId, int attemptNumber,
                                                       String errorMessage, long durationMs,
                                                       Instant now) {
        return new NotificationAttempt(notificationId, attemptNumber, AttemptOutcome.PERMANENT_FAILURE,
                null, errorMessage, clampDuration(durationMs), now);
    }

    /**
     * Clamps to {@code int} range and to non-negative.
     *
     * <p>A negative duration is impossible from a monotonic clock but trivially
     * possible from a wall clock that stepped backwards mid-call — and the
     * CHECK constraint would reject it, failing the write that records why
     * delivery failed. The measurement is not worth losing the record over.
     */
    private static Integer clampDuration(long millis) {
        if (millis < 0) {
            return 0;
        }
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }

    public boolean isSuccess() {
        return outcome == AttemptOutcome.SUCCESS;
    }

    public boolean isRetryable() {
        return outcome == AttemptOutcome.TRANSIENT_FAILURE;
    }

    public Long getId() {
        return id;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public AttemptOutcome getOutcome() {
        return outcome;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NotificationAttempt that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    /**
     * Constant, which is the standard recipe for a JPA entity with a generated
     * id: it stays stable across the transition from transient to persistent,
     * which {@code Objects.hash(id)} would not — an entity already in a
     * {@code HashSet} would become unfindable the moment it was saved.
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(NotificationAttempt.class);
    }

    @Override
    public String toString() {
        return "NotificationAttempt{notificationId=%s, attempt=%d, outcome=%s, durationMs=%s}"
                .formatted(notificationId, attemptNumber, outcome, durationMs);
    }
}
