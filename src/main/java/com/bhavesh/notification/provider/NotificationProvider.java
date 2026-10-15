package com.bhavesh.notification.provider;

import com.bhavesh.notification.notification.domain.AttemptOutcome;
import com.bhavesh.notification.notification.domain.NotificationChannel;

/**
 * The boundary between this service and whatever actually delivers a message.
 *
 * <p>An interface rather than a direct call into the simulator, for one
 * concrete reason: swapping in a real gateway should be a new implementation of
 * this type and a bean definition, not an edit to the delivery processor.
 * Everything interesting about the pipeline — the outbox, the idempotency key,
 * the retry classification, the attempt history — lives on this side of the
 * boundary and does not change with the provider.
 *
 * <p>It also makes the processor testable without any provider at all: a stub
 * returning {@link DeliveryResult#transientFailure} exercises the retry path
 * directly.
 *
 * <h2>Implementations must not throw for a delivery failure</h2>
 * A rejected message is an <em>answer</em>, not an error, and the caller needs
 * to record it as one. Throwing is reserved for genuinely unexpected
 * conditions; the ordinary failure cases are modelled as a
 * {@link DeliveryResult} so the caller is forced to handle them.
 *
 * <h2>Classification is the adapter's job</h2>
 * Only the adapter knows what its provider's errors mean — whether a 429 is
 * retryable, whether a particular bounce code is permanent. The processor acts
 * on {@link DeliveryResult#outcome()} and does not second-guess it.
 */
public interface NotificationProvider {

    /** Which channel this adapter serves. Used to select it from the registry. */
    NotificationChannel channel();

    /**
     * Attempts delivery.
     *
     * @param request what to send, and to whom
     * @return the outcome, never null, and never an exception for an ordinary
     *         delivery failure
     */
    DeliveryResult send(DeliveryRequest request);

    /**
     * Whether this adapter is simulating.
     *
     * <p>Surfaced all the way up into the API response, because a client that
     * believed a simulated send was real would be badly misled — it might, for
     * instance, stop retrying a password reset that never actually went
     * anywhere.
     */
    boolean isSimulated();

    /**
     * What to deliver.
     *
     * @param notificationId used as the provider-side idempotency key where the
     *                       provider supports one. Several real gateways accept
     *                       a client-supplied key and will not send twice for
     *                       the same value — which is the only genuine defence
     *                       against a duplicate send, since at-least-once
     *                       processing means the adapter may legitimately be
     *                       called twice for one notification.
     * @param attemptNumber  included so an adapter can vary behaviour across
     *                       retries, and so simulated outcomes can be scripted
     *                       per attempt in tests.
     */
    record DeliveryRequest(
            java.util.UUID notificationId,
            NotificationChannel channel,
            String recipient,
            String subject,
            String body,
            int attemptNumber,
            String metadata
    ) {
        /** Masked for logging. Never log {@link #recipient()} directly. */
        public String maskedRecipient() {
            return channel.mask(recipient);
        }
    }

    /**
     * The outcome of one delivery attempt.
     *
     * @param outcome           success, or which kind of failure
     * @param providerReference the provider's own id for the message; null on
     *                          failure. This is what a support conversation
     *                          with the provider is conducted in terms of.
     * @param errorMessage      sanitised; null on success
     */
    record DeliveryResult(
            AttemptOutcome outcome,
            String providerReference,
            String errorMessage
    ) {

        public static DeliveryResult success(String providerReference) {
            return new DeliveryResult(AttemptOutcome.SUCCESS, providerReference, null);
        }

        /** Worth retrying: a timeout, a 5xx, a rate limit, a dropped connection. */
        public static DeliveryResult transientFailure(String reason) {
            return new DeliveryResult(AttemptOutcome.TRANSIENT_FAILURE, null, reason);
        }

        /** Not worth retrying: a malformed recipient, rejected content, a hard bounce. */
        public static DeliveryResult permanentFailure(String reason) {
            return new DeliveryResult(AttemptOutcome.PERMANENT_FAILURE, null, reason);
        }

        public boolean isSuccess() {
            return outcome == AttemptOutcome.SUCCESS;
        }

        public boolean isRetryable() {
            return outcome == AttemptOutcome.TRANSIENT_FAILURE;
        }
    }
}
