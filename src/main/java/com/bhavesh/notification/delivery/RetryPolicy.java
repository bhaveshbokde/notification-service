package com.bhavesh.notification.delivery;

import com.bhavesh.notification.config.DeliveryProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * Decides whether and when to retry a failed delivery.
 *
 * <h2>Exponential backoff</h2>
 * With the defaults — 2s initial, ×2, capped at 60s — the waits are 2s, 4s, 8s.
 * Exponential rather than fixed because the usual cause of a transient failure
 * is an overloaded or rate-limiting provider, and retrying at a fixed interval
 * keeps it overloaded. Each wait gives it more room than the last.
 *
 * <h2>Jitter</h2>
 * Without it, every notification that failed during a provider's 30-second
 * outage would retry at the same instant — a thundering herd that can knock the
 * provider over again just as it recovers, producing a second outage and
 * another synchronised herd.
 *
 * <p>The jitter here is ±{@code jitterFactor} around the computed delay
 * ("equal jitter"), rather than full jitter over {@code [0, delay]}. Full
 * jitter spreads better but can retry almost immediately, which defeats the
 * point of backing off at all when the provider needs a moment.
 *
 * <h2>Determinism in tests</h2>
 * Setting {@code jitter-factor: 0} makes {@link #nextDelay} a pure function of
 * the attempt number, so a test can assert exact delays. That is why jitter is
 * configuration rather than a hard-coded {@code Math.random()}.
 */
@Component
public class RetryPolicy {

    private final DeliveryProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RetryPolicy(DeliveryProperties properties) {
        this.properties = properties;
    }

    /**
     * Whether another attempt should be made.
     *
     * <p>Two conditions, and both matter:
     *
     * <ul>
     *   <li>The failure must be <em>transient</em>. Retrying a permanent
     *       failure burns the whole attempt budget re-sending to an address
     *       that will never accept it — and on a paid provider, burns money
     *       doing it.</li>
     *   <li>There must be budget left. An unbounded retry loop against a
     *       provider that is permanently rejecting a message is a way to fill
     *       logs and spend money without ever succeeding.</li>
     * </ul>
     *
     * @param attemptsSoFar attempts already made, including the one that just failed
     */
    public boolean shouldRetry(boolean transientFailure, int attemptsSoFar, int maxAttempts) {
        return transientFailure && attemptsSoFar < maxAttempts;
    }

    /**
     * The delay before attempt number {@code nextAttemptNumber}.
     *
     * @param nextAttemptNumber 1-based. The delay before attempt 2 — the first
     *                          retry — is the configured initial backoff.
     */
    public Duration nextDelay(int nextAttemptNumber) {
        if (nextAttemptNumber <= 1) {
            return Duration.ZERO;
        }

        // attempt 2 -> initial, attempt 3 -> initial × multiplier, ...
        int exponent = nextAttemptNumber - 2;
        double base = properties.initialBackoff().toMillis()
                * Math.pow(properties.backoffMultiplier(), exponent);

        // Cap BEFORE jitter. Capping afterwards would let jitter push the delay
        // back above the configured maximum, which is then not a maximum.
        double capped = Math.min(base, properties.maxBackoff().toMillis());

        return Duration.ofMillis(Math.max(0L, applyJitter(capped)));
    }

    /**
     * Spreads the delay by ±{@code jitterFactor}.
     *
     * <p>Returns the input unchanged when the factor is zero, so tests that
     * disable jitter get exact, assertable delays rather than
     * nearly-exact ones.
     */
    private long applyJitter(double millis) {
        double factor = properties.jitterFactor();
        if (factor <= 0.0) {
            return Math.round(millis);
        }
        // nextDouble() in [0,1) mapped to [-factor, +factor).
        double offset = (random.nextDouble() * 2.0 - 1.0) * factor;
        return Math.round(millis * (1.0 + offset));
    }

    public int maxAttempts() {
        return properties.maxAttempts();
    }
}
