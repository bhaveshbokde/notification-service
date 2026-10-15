package com.bhavesh.notification.deadletter.domain;

/**
 * Whether a dead-lettered event has been dealt with.
 *
 * <p>There is deliberately no automatic resolution. A dead letter means the
 * pipeline could not process a message, and the reason is almost always
 * something a human needs to look at — a schema mismatch, a bug, a message
 * from a version of the producer nobody expected. Automatically replaying those
 * on a timer would turn a visible problem into an invisible retry loop.
 */
public enum DeadLetterStatus {

    /** Waiting for an operator. This is what the backlog query looks for. */
    UNRESOLVED,

    /** An administrator re-submitted it for processing. */
    REPLAYED,

    /** An administrator judged it unrecoverable and closed it. */
    DISCARDED
}
