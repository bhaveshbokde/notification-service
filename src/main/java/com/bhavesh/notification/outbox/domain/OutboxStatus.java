package com.bhavesh.notification.outbox.domain;

/**
 * Publication state of an outbox row.
 *
 * <pre>
 *   PENDING ──► PUBLISHING ──► PUBLISHED   (terminal)
 *      ▲             │
 *      └─────────────┤                     publish failed, budget remains
 *                    ▼
 *                 FAILED                   (terminal; needs an operator)
 * </pre>
 */
public enum OutboxStatus {

    /** Written and committed; waiting for a publisher. */
    PENDING,

    /**
     * Claimed by a publisher; the send is in flight.
     *
     * <p>A row can be stuck here if the publisher dies mid-send, which is
     * exactly what the lease timeout exists to recover from.
     */
    PUBLISHING,

    /** The broker acknowledged the record. Terminal. */
    PUBLISHED,

    /**
     * Repeated publish failures exhausted the attempt budget.
     *
     * <p>Terminal only in the sense that the publisher stops trying. It is a
     * signal that something is wrong which retrying will not fix — an
     * oversized payload, a topic that does not exist and cannot be
     * auto-created, a misconfigured broker — and it needs a human.
     */
    FAILED;

    public boolean isTerminal() {
        return this == PUBLISHED || this == FAILED;
    }

    /** Whether a publisher should consider picking this row up. */
    public boolean isPublishable() {
        return this == PENDING;
    }
}
