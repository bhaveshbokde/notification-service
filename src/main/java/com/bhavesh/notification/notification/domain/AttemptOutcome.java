package com.bhavesh.notification.notification.domain;

/**
 * What a provider call produced.
 *
 * <p>The distinction between the two failure kinds is the single most important
 * decision in the retry policy, and getting it wrong is expensive in both
 * directions:
 *
 * <ul>
 *   <li>Retrying a {@link #PERMANENT_FAILURE} burns the entire attempt budget
 *       re-sending to an address that will never accept it, and on a paid
 *       provider it burns money too.</li>
 *   <li>Not retrying a {@link #TRANSIENT_FAILURE} throws away a notification
 *       because the provider happened to be rate-limiting for two seconds.</li>
 * </ul>
 *
 * <p>Classification is the provider adapter's job, because only the adapter
 * knows what its provider's errors mean. The processor acts on the answer and
 * does not second-guess it.
 */
public enum AttemptOutcome {

    /** The provider accepted the message. */
    SUCCESS,

    /**
     * Worth trying again: a timeout, a 5xx, a rate limit, a dropped connection.
     * The request was fine; the provider was temporarily unable to take it.
     */
    TRANSIENT_FAILURE,

    /**
     * Not worth trying again: a malformed recipient, a rejected sender, content
     * the provider refuses, a hard bounce. Retrying would produce the identical
     * answer, so the notification fails immediately rather than after three
     * identical round trips.
     */
    PERMANENT_FAILURE
}
