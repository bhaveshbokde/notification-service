package com.bhavesh.notification.ratelimit;

import com.bhavesh.notification.config.RateLimitProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * A fixed-window rate limiter backed by Redis.
 *
 * <h2>The algorithm, and why this one</h2>
 * One counter per caller per window:
 *
 * <pre>
 *   INCR  ratelimit:{callerId}:{windowStart}
 *   EXPIRE ... windowSeconds          (only when the counter is new)
 * </pre>
 *
 * {@code INCR} on a missing key creates it at 1 and returns 1, atomically. That
 * single property is what makes this safe across instances without a lock: two
 * application instances incrementing concurrently get 1 and 2, never 1 and 1.
 *
 * <p>The window is encoded <em>in the key</em>, so expiry is the only cleanup
 * needed — there is no sweep and no state to reconcile. The TTL is set only on
 * the first increment of a window; setting it on every call would slide the
 * window forward and a steady stream of requests would keep it alive forever.
 *
 * <h2>The honest weakness</h2>
 * A fixed window allows a burst across the boundary: a caller can send
 * {@code capacity} requests in the last instant of one window and
 * {@code capacity} more in the first instant of the next, so the true
 * short-term rate is up to 2× the configured limit.
 *
 * <p>A sliding-window log fixes that but needs a sorted set per caller, a
 * {@code ZREMRANGEBYSCORE} on every call and far more memory. A token bucket
 * fixes it too but needs either a Lua script or a read-modify-write that is not
 * atomic. For protecting a notification intake API the 2× boundary burst is not
 * worth that complexity — and stating the limitation is better than implying an
 * accuracy this does not have.
 *
 * <h2>Fail-open</h2>
 * If Redis is unreachable the request is <strong>allowed</strong> and a warning
 * is logged. A rate limiter is a protection mechanism, not a correctness one;
 * failing closed would turn a cache outage into a total outage of notification
 * intake, which is a strictly worse incident. Configurable via
 * {@code notification.rate-limit.fail-open} for the rare case where the thing
 * being protected genuinely cannot survive a burst.
 *
 * <p>Note what this does <em>not</em> guard: idempotency and the notification
 * records themselves live in PostgreSQL precisely so that a Redis outage
 * degrades performance rather than correctness.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /**
     * Key prefix. Keys contain the numeric user id and a window number — never
     * a username, a token or anything else identifying. Redis keys turn up in
     * {@code MONITOR} output, slow logs and memory dumps, so they are treated
     * as log output.
     */
    private static final String KEY_PREFIX = "ratelimit:notifications:";

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final Counter allowedCounter;
    private final Counter rejectedCounter;
    private final Counter failOpenCounter;

    public RateLimiter(StringRedisTemplate redis,
                       RateLimitProperties properties,
                       MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        this.allowedCounter = Counter.builder("notification.ratelimit.allowed")
                .description("Requests permitted by the rate limiter").register(meterRegistry);
        this.rejectedCounter = Counter.builder("notification.ratelimit.rejected")
                .description("Requests rejected with 429").register(meterRegistry);
        this.failOpenCounter = Counter.builder("notification.ratelimit.failopen")
                .description("Requests allowed because Redis was unreachable")
                .register(meterRegistry);
    }

    /**
     * Consumes one unit of a caller's budget.
     *
     * @param callerId the authenticated user id — never a token or a username
     * @return the decision, including what to put in the response headers
     */
    public Decision tryConsume(Long callerId) {
        if (!properties.enabled()) {
            return Decision.allowed(properties.capacity(), properties.capacity(), Duration.ZERO);
        }

        long windowSeconds = properties.window().toSeconds();
        long now = Instant.now().getEpochSecond();
        long windowNumber = now / windowSeconds;
        long secondsUntilReset = windowSeconds - (now % windowSeconds);

        String key = KEY_PREFIX + callerId + ":" + windowNumber;

        try {
            Long count = redis.opsForValue().increment(key);

            if (count == null) {
                // Should not happen — INCR always returns a value — but a null
                // here would mean the limiter is not actually working, so it is
                // treated as unavailable rather than silently allowed.
                return failOpen("Redis INCR returned null");
            }

            if (count == 1L) {
                // Only on the first increment of this window. Setting the TTL
                // on every call would slide the window and a steady stream
                // would keep the key alive indefinitely.
                redis.expire(key, Duration.ofSeconds(windowSeconds));
            }

            if (count > properties.capacity()) {
                rejectedCounter.increment();
                log.debug("Rate limit exceeded for caller {}: {} > {}",
                        callerId, count, properties.capacity());
                return Decision.rejected(properties.capacity(),
                        Duration.ofSeconds(secondsUntilReset));
            }

            allowedCounter.increment();
            return Decision.allowed(properties.capacity(),
                    (int) (properties.capacity() - count),
                    Duration.ofSeconds(secondsUntilReset));

        } catch (Exception e) {
            return failOpen(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Decision failOpen(String reason) {
        if (!properties.failOpen()) {
            // Configured to fail closed. The caller turns this into a 503
            // rather than a 429 — the limit was not exceeded, the limiter is
            // broken, and conflating the two would send clients backing off for
            // the wrong reason.
            log.error("Rate limiter unavailable and fail-open is disabled: {}", reason);
            return Decision.unavailable();
        }

        failOpenCounter.increment();
        log.warn("Rate limiter unavailable; allowing the request (fail-open): {}", reason);
        return Decision.allowed(properties.capacity(), properties.capacity(), Duration.ZERO);
    }

    /**
     * The outcome of a rate-limit check.
     *
     * @param limit      the configured capacity, for {@code X-RateLimit-Limit}
     * @param remaining  budget left in this window
     * @param resetAfter time until the window rolls over, for {@code Retry-After}
     */
    public record Decision(boolean allowed, boolean limiterAvailable,
                           int limit, int remaining, Duration resetAfter) {

        static Decision allowed(int limit, int remaining, Duration resetAfter) {
            return new Decision(true, true, limit, Math.max(0, remaining), resetAfter);
        }

        static Decision rejected(int limit, Duration resetAfter) {
            return new Decision(false, true, limit, 0, resetAfter);
        }

        static Decision unavailable() {
            return new Decision(false, false, 0, 0, Duration.ZERO);
        }
    }
}
