package com.bhavesh.notification.ratelimit;

import com.bhavesh.notification.config.RateLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rate limiter.
 *
 * <p>Redis is mocked here, which is the right boundary: {@code INCR}'s
 * atomicity is a Redis guarantee, not something this class implements. What
 * this class owns is the decision logic — when to reject, what to report, and
 * above all what to do when Redis is unreachable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    private RateLimiter limiter(int capacity, boolean enabled, boolean failOpen) {
        return new RateLimiter(redis,
                new RateLimitProperties(enabled, capacity, Duration.ofMinutes(1), failOpen),
                new SimpleMeterRegistry());
    }

    @Nested
    @DisplayName("within the limit")
    class WithinLimit {

        @Test
        @DisplayName("allows the request and reports the remaining budget")
        void allowsAndReportsRemaining() {
            when(valueOps.increment(anyString())).thenReturn(3L);

            var decision = limiter(100, true, true).tryConsume(7L);

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.limit()).isEqualTo(100);
            assertThat(decision.remaining()).isEqualTo(97);
            assertThat(decision.limiterAvailable()).isTrue();
        }

        /**
         * Setting the TTL on every call would slide the window forward, and a
         * steady stream of requests would keep the key alive indefinitely — so
         * the window would never reset and the caller would be locked out for
         * good.
         */
        @Test
        @DisplayName("the TTL is set only on the first request of a window")
        void ttlSetOnceOnly() {
            when(valueOps.increment(anyString())).thenReturn(1L);
            limiter(100, true, true).tryConsume(7L);
            verify(redis, times(1)).expire(anyString(), any(Duration.class));

            org.mockito.Mockito.reset(redis);
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenReturn(2L);
            limiter(100, true, true).tryConsume(7L);
            verify(redis, never()).expire(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("the boundary request is allowed, not rejected")
        void boundaryIsInclusive() {
            when(valueOps.increment(anyString())).thenReturn(100L);

            var decision = limiter(100, true, true).tryConsume(7L);

            assertThat(decision.allowed()).as("the 100th of 100 is within the limit").isTrue();
            assertThat(decision.remaining()).isZero();
        }
    }

    @Nested
    @DisplayName("over the limit")
    class OverLimit {

        @Test
        @DisplayName("rejects and reports when the window resets")
        void rejects() {
            when(valueOps.increment(anyString())).thenReturn(101L);

            var decision = limiter(100, true, true).tryConsume(7L);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.limiterAvailable())
                    .as("the limiter worked — it is the caller who went too fast")
                    .isTrue();
            assertThat(decision.remaining()).isZero();
            assertThat(decision.resetAfter()).isPositive();
        }
    }

    @Nested
    @DisplayName("Redis unavailable")
    class RedisDown {

        /**
         * A rate limiter is a protection mechanism, not a correctness one.
         * Failing closed would turn a cache outage into a total outage of
         * notification intake, which is a strictly worse incident than briefly
         * serving unlimited requests.
         */
        @Test
        @DisplayName("fails OPEN by default — a cache outage must not stop notifications")
        void failsOpen() {
            when(valueOps.increment(anyString()))
                    .thenThrow(new RedisConnectionFailureException("connection refused"));

            var decision = limiter(100, true, true).tryConsume(7L);

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.limiterAvailable()).isTrue();
        }

        /**
         * The distinction matters to a client: 429 means "you are going too
         * fast", 503 means "we cannot tell". Conflating them would have clients
         * backing off for the wrong reason.
         */
        @Test
        @DisplayName("fails CLOSED when configured to, and says the limiter is unavailable")
        void failsClosedWhenConfigured() {
            when(valueOps.increment(anyString()))
                    .thenThrow(new RedisConnectionFailureException("connection refused"));

            var decision = limiter(100, true, false).tryConsume(7L);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.limiterAvailable())
                    .as("so the caller answers 503, not 429")
                    .isFalse();
        }

        @Test
        @DisplayName("a null from INCR is treated as unavailable, not as zero usage")
        void nullIncrementIsTreatedAsFailure() {
            when(valueOps.increment(anyString())).thenReturn(null);

            var decision = limiter(100, true, false).tryConsume(7L);

            assertThat(decision.limiterAvailable()).isFalse();
        }
    }

    @Test
    @DisplayName("when disabled, every request is allowed and Redis is never touched")
    void disabledBypassesRedisEntirely() {
        var decision = limiter(100, false, true).tryConsume(7L);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(100);
        verify(valueOps, never()).increment(anyString());
    }

    /**
     * Redis keys turn up in {@code MONITOR} output, slow logs and memory dumps,
     * so they are treated as log output: a numeric user id, never a username or
     * a token.
     */
    @Test
    @DisplayName("the key contains the user id and nothing identifying")
    void keyCarriesNoSensitiveData() {
        when(valueOps.increment(anyString())).thenReturn(1L);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        limiter(100, true, true).tryConsume(42L);
        verify(valueOps).increment(captor.capture());

        String key = captor.getValue();
        assertThat(key).contains("42").startsWith("ratelimit:");
        assertThat(key)
                .as("a token or username here would leak into Redis diagnostics")
                .doesNotContain("Bearer").doesNotContain("eyJ");
    }

    @Test
    @DisplayName("different callers get independent budgets")
    void callersAreIndependent() {
        when(valueOps.increment(anyString())).thenReturn(1L);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        RateLimiter limiter = limiter(100, true, true);
        limiter.tryConsume(1L);
        limiter.tryConsume(2L);

        verify(valueOps, times(2)).increment(captor.capture());
        assertThat(captor.getAllValues().get(0)).isNotEqualTo(captor.getAllValues().get(1));
    }
}
