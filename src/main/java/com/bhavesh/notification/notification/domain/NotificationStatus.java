package com.bhavesh.notification.notification.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of a notification, with the legal transitions encoded.
 *
 * <p>Keeping the transition table <em>in the enum</em> rather than as an if-else
 * chain inside a service means the rule exists in exactly one place. Adding a
 * state is a change to this map; no service needs to be audited for a missing
 * branch.
 *
 * <pre>
 *                    ┌──────────────────────────────┐
 *                    ▼                              │
 *   PENDING ──► PROCESSING ──► RETRY_SCHEDULED ──────┘
 *      │             │ │
 *      │             │ └──────────► SENT              (terminal)
 *      │             └────────────► FAILED            (terminal)
 *      └──────────────────────────► DEAD_LETTERED     (terminal)
 * </pre>
 *
 * <h2>Why RETRY_SCHEDULED is a state and not a flag</h2>
 * It answers a question an operator actually asks: "is this stuck, or is it
 * waiting?" A boolean {@code retrying} column alongside PROCESSING would make
 * those two indistinguishable in a status listing, which is precisely when you
 * need to tell them apart.
 *
 * <h2>Why DEAD_LETTERED is distinct from FAILED</h2>
 * FAILED means the pipeline worked and the provider said no — there is a full
 * attempt history explaining why. DEAD_LETTERED means the pipeline could not
 * process the message at all: a malformed event, a bug in the consumer, a
 * notification that vanished. Conflating them hides real incidents inside
 * ordinary delivery failures.
 */
public enum NotificationStatus {

    /** Accepted and persisted; no consumer has picked it up yet. */
    PENDING,

    /** Claimed by a consumer. A provider call is in flight. */
    PROCESSING,

    /** A transient failure occurred; {@code next_attempt_at} says when to retry. */
    RETRY_SCHEDULED,

    /** The provider accepted it. Terminal. */
    SENT,

    /** A permanent failure, or retries exhausted. Terminal. */
    FAILED,

    /** Could not be processed at all; parked for an operator. Terminal. */
    DEAD_LETTERED;

    private static final Map<NotificationStatus, Set<NotificationStatus>> ALLOWED;

    static {
        // Built in a static block rather than per-constant fields: a constant
        // cannot reference another constant that has not been initialised yet,
        // and PENDING must name PROCESSING.
        Map<NotificationStatus, Set<NotificationStatus>> t = new EnumMap<>(NotificationStatus.class);

        t.put(PENDING, EnumSet.of(PROCESSING, DEAD_LETTERED));

        // PROCESSING -> PROCESSING is deliberately absent: a second consumer
        // must not be able to re-claim a notification another one is working on.
        t.put(PROCESSING, EnumSet.of(SENT, FAILED, RETRY_SCHEDULED, DEAD_LETTERED));

        t.put(RETRY_SCHEDULED, EnumSet.of(PROCESSING, FAILED, DEAD_LETTERED));

        // Terminal.
        t.put(SENT, EnumSet.noneOf(NotificationStatus.class));
        t.put(FAILED, EnumSet.noneOf(NotificationStatus.class));
        t.put(DEAD_LETTERED, EnumSet.noneOf(NotificationStatus.class));

        // Each set is wrapped individually, not just the map. unmodifiableMap
        // stops put/remove on the map itself but hands out the *same* mutable
        // EnumSet instances — so a caller of allowedTransitions() could add a
        // transition and keep it for the lifetime of the JVM.
        t.replaceAll((status, allowed) -> Collections.unmodifiableSet(allowed));
        ALLOWED = Collections.unmodifiableMap(t);
    }

    public boolean canTransitionTo(NotificationStatus target) {
        return target != null && ALLOWED.get(this).contains(target);
    }

    public Set<NotificationStatus> allowedTransitions() {
        return ALLOWED.get(this);
    }

    /** No further transition is possible. The work is over, one way or another. */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Still in the pipeline — a consumer will act on it again. */
    public boolean isActive() {
        return !isTerminal();
    }

    /**
     * Whether a consumer may claim this notification for a delivery attempt.
     *
     * <p>PROCESSING is excluded on purpose: that is what stops two consumers
     * handling the same notification when Kafka redelivers a message.
     */
    public boolean isClaimable() {
        return this == PENDING || this == RETRY_SCHEDULED;
    }
}
