package com.bhavesh.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Binds {@code notification.rate-limit.*}.
 *
 * @param capacity requests permitted per window, per authenticated caller.
 *
 * @param window   the window length. A fixed window rather than a sliding log:
 *                 a sliding window is more accurate at the boundary but needs a
 *                 sorted set per caller and a cleanup pass. The documented
 *                 weakness of a fixed window is that a caller can send
 *                 {@code capacity} requests at the end of one window and
 *                 {@code capacity} more at the start of the next — a 2x burst
 *                 across the boundary. Acceptable here, and recorded in
 *                 docs/ARCHITECTURE.md rather than left for someone to discover.
 *
 * @param failOpen what to do when Redis is unreachable. True means allow the
 *                 request and log a warning.
 *                 <p>
 *                 This is the right default and the reasoning is worth being
 *                 explicit about: a rate limiter is a <em>protection</em>
 *                 mechanism, not a correctness one. Failing closed would turn a
 *                 cache outage into a total outage of notification intake,
 *                 which is a strictly worse incident than briefly serving
 *                 unlimited requests. Set false only where the thing being
 *                 protected cannot survive a burst.
 */
@ConfigurationProperties(prefix = "notification.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") int capacity,
        @DefaultValue("PT1M") Duration window,
        @DefaultValue("true") boolean failOpen
) {
}
