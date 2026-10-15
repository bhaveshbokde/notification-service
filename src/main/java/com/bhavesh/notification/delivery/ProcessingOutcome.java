package com.bhavesh.notification.delivery;

import java.time.Duration;

/**
 * What {@link DeliveryProcessor#process} did.
 *
 * <p>The Kafka consumer acts on this rather than on an exception, because most
 * of these outcomes are ordinary operation rather than errors — and using
 * exceptions for them would mean the consumer could not tell "this failed and
 * should be retried" from "this was a duplicate and should be acknowledged".
 *
 * <p>A sealed-style value class rather than a bare enum, because
 * {@link #RETRY_SCHEDULED} needs to carry the delay: the consumer uses it to
 * decide when to republish the retry event.
 */
public final class ProcessingOutcome {

    /** Delivered. Nothing further to do. */
    public static final ProcessingOutcome SENT = new ProcessingOutcome(Kind.SENT, null);

    /** Terminally failed — permanent, or retries exhausted. Nothing further to do. */
    public static final ProcessingOutcome FAILED = new ProcessingOutcome(Kind.FAILED, null);

    /**
     * Another consumer already handled this, or it is a duplicate event.
     *
     * <p>Expected under at-least-once delivery and <strong>not</strong> an
     * error. The consumer acknowledges the message and moves on; retrying would
     * just produce the same answer.
     */
    public static final ProcessingOutcome ALREADY_HANDLED =
            new ProcessingOutcome(Kind.ALREADY_HANDLED, null);

    /**
     * The event referenced a notification that does not exist.
     *
     * <p>A pipeline fault, not a delivery failure: the consumer dead-letters
     * it, because retrying cannot conjure the row into existence.
     */
    public static final ProcessingOutcome NOT_FOUND = new ProcessingOutcome(Kind.NOT_FOUND, null);

    private final Kind kind;
    private final Duration retryDelay;

    private ProcessingOutcome(Kind kind, Duration retryDelay) {
        this.kind = kind;
        this.retryDelay = retryDelay;
    }

    /** A transient failure; republish the event after {@code delay}. */
    public static ProcessingOutcome retryScheduled(Duration delay) {
        return new ProcessingOutcome(Kind.RETRY_SCHEDULED, delay);
    }

    public Kind kind() {
        return kind;
    }

    /** Non-null only for {@link Kind#RETRY_SCHEDULED}. */
    public Duration retryDelay() {
        return retryDelay;
    }

    public boolean needsRetryEvent() {
        return kind == Kind.RETRY_SCHEDULED;
    }

    public boolean shouldDeadLetter() {
        return kind == Kind.NOT_FOUND;
    }

    @Override
    public String toString() {
        return retryDelay == null ? kind.name() : kind + "(" + retryDelay.toSeconds() + "s)";
    }

    public enum Kind {
        SENT,
        FAILED,
        RETRY_SCHEDULED,
        ALREADY_HANDLED,
        NOT_FOUND
    }
}
