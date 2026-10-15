package com.bhavesh.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Binds {@code notification.delivery.*} — the retry policy.
 *
 * @param maxAttempts       total delivery attempts including the first. Three
 *                          means one try and two retries. Bounded on purpose:
 *                          an unbounded retry loop against a provider that is
 *                          permanently rejecting a message is a way to spend
 *                          money and fill logs without ever succeeding.
 *
 * @param initialBackoff    delay before the first retry.
 *
 * @param backoffMultiplier exponential growth factor. With a 2s initial delay
 *                          and a 2.0 multiplier the waits are 2s, 4s, 8s.
 *                          Exponential rather than fixed because the common
 *                          cause of a transient failure is an overloaded
 *                          provider, and hammering it at a fixed interval
 *                          keeps it overloaded.
 *
 * @param maxBackoff        cap. Without one, exponential growth reaches hours
 *                          and a notification silently stops being a
 *                          notification.
 *
 * @param jitterFactor      randomisation, ±20% by default. If a provider has a
 *                          30-second outage, every notification that failed
 *                          during it would otherwise retry at the same instant
 *                          — a thundering herd that can knock the provider over
 *                          again just as it recovers. Jitter spreads them.
 *                          Set to 0 in tests that assert exact delays.
 */
@ConfigurationProperties(prefix = "notification.delivery")
public record DeliveryProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("PT2S") Duration initialBackoff,
        @DefaultValue("2.0") double backoffMultiplier,
        @DefaultValue("PT1M") Duration maxBackoff,
        @DefaultValue("0.2") double jitterFactor
) {
}
