package com.bhavesh.notification.common.exception;

import com.bhavesh.notification.notification.domain.NotificationStatus;

/**
 * An illegal notification status transition was attempted.
 *
 * <p>Carries both statuses so callers can act on <em>which</em> transition
 * failed rather than parsing a message. That matters in one specific place: the
 * Kafka consumer treats "could not claim, because it is no longer claimable" as
 * a duplicate event to be acknowledged quietly, not as an error to retry. A
 * bare exception type would leave it unable to tell that case from a genuine
 * bug.
 *
 * <p>Maps to 422: the request was understood and well-formed, but the
 * notification is not in a state where it can be carried out.
 */
public class InvalidStateTransitionException extends ApiException {

    private final transient NotificationStatus from;
    private final transient NotificationStatus to;

    public InvalidStateTransitionException(String detail,
                                           NotificationStatus from,
                                           NotificationStatus to) {
        super(ErrorCode.INVALID_STATUS_TRANSITION, detail);
        this.from = from;
        this.to = to;
    }

    public NotificationStatus from() {
        return from;
    }

    public NotificationStatus to() {
        return to;
    }

    /**
     * True when the transition failed because the notification had already
     * moved on — the signature of a duplicate or late Kafka event rather than a
     * defect.
     */
    public boolean isAlreadyHandled() {
        return from != null && (from.isTerminal() || from == NotificationStatus.PROCESSING);
    }
}
