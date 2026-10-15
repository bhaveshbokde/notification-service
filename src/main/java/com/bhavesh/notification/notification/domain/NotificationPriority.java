package com.bhavesh.notification.notification.domain;

/**
 * Relative urgency, used to order work when the pipeline is backed up.
 *
 * <p>A password-reset email and a marketing digest should not share a queue
 * position. When the outbox has a backlog the publisher drains HIGH first, so
 * the notification a user is actively waiting on is not stuck behind ten
 * thousand newsletters.
 *
 * <p>Priority affects <em>ordering</em>, never whether something is delivered.
 * A LOW notification is not dropped under load; it waits. Shedding load by
 * discarding notifications would need to be an explicit, documented decision,
 * not a side effect of a priority field.
 */
public enum NotificationPriority {

    /** Bulk and non-urgent: digests, newsletters, marketing. */
    LOW,

    /** The default. Transactional messages with no particular deadline. */
    NORMAL,

    /** Someone is waiting: one-time codes, password resets, security alerts. */
    HIGH
}
