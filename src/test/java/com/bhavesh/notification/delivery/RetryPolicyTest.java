package com.bhavesh.notification.delivery;

import com.bhavesh.notification.config.DeliveryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry policy.
 *
 * <p>Most of these tests set {@code jitterFactor} to zero, which makes
 * {@link RetryPolicy#nextDelay} a pure function of the attempt number and the
 * delays exactly assertable. That is precisely why jitter is configuration
 * rather than a hard-coded {@code Math.random()} — a policy that cannot be
 * pinned down cannot be tested.
 */
class RetryPolicyTest {

    private static RetryPolicy policy(int maxAttempts, Duration initial, double multiplier,
                                      Duration max, double jitter) {
        return new RetryPolicy(new DeliveryProperties(maxAttempts, initial, multiplier, max, jitter));
    }

    /** The production defaults, with jitter disabled so delays are deterministic. */
    private static RetryPolicy deterministicDefaults() {
        return policy(3, Duration.ofSeconds(2), 2.0, Duration.ofMinutes(1), 0.0);
    }

    @Nested
    @DisplayName("shouldRetry")
    class ShouldRetry {

        /**
         * Retrying a permanent failure burns the whole attempt budget
         * re-sending to an address that will never accept it — and on a paid
         * provider, burns money doing it.
         */
        @Test
        @DisplayName("a permanent failure is never retried, however much budget remains")
        void permanentFailureIsNotRetried() {
            RetryPolicy p = deterministicDefaults();

            assertThat(p.shouldRetry(false, 1, 3)).isFalse();
            assertThat(p.shouldRetry(false, 0, 10)).isFalse();
        }

        @Test
        @DisplayName("a transient failure is retried while budget remains")
        void transientFailureIsRetried() {
            RetryPolicy p = deterministicDefaults();

            assertThat(p.shouldRetry(true, 1, 3)).isTrue();
            assertThat(p.shouldRetry(true, 2, 3)).isTrue();
        }

        @Test
        @DisplayName("a transient failure stops once the budget is spent")
        void exhaustedBudgetStopsRetrying() {
            RetryPolicy p = deterministicDefaults();

            assertThat(p.shouldRetry(true, 3, 3)).isFalse();
            assertThat(p.shouldRetry(true, 4, 3)).isFalse();
        }

        @Test
        @DisplayName("maxAttempts of 1 means no retry at all")
        void singleAttemptBudget() {
            assertThat(deterministicDefaults().shouldRetry(true, 1, 1)).isFalse();
        }
    }

    @Nested
    @DisplayName("nextDelay — exponential backoff")
    class Backoff {

        @ParameterizedTest(name = "attempt {0} waits {1}ms")
        @CsvSource({
                "2,  2000",   // first retry: the initial backoff
                "3,  4000",   // ×2
                "4,  8000",
                "5, 16000",
                "6, 32000",
        })
        @DisplayName("doubles each time")
        void doublesEachAttempt(int attemptNumber, long expectedMillis) {
            assertThat(deterministicDefaults().nextDelay(attemptNumber))
                    .isEqualTo(Duration.ofMillis(expectedMillis));
        }

        @Test
        @DisplayName("the first attempt has no delay")
        void firstAttemptIsImmediate() {
            assertThat(deterministicDefaults().nextDelay(1)).isEqualTo(Duration.ZERO);
            assertThat(deterministicDefaults().nextDelay(0)).isEqualTo(Duration.ZERO);
        }

        /**
         * Without a cap, exponential growth reaches hours and a notification
         * silently stops being a notification.
         */
        @Test
        @DisplayName("growth is capped")
        void capped() {
            RetryPolicy p = policy(10, Duration.ofSeconds(2), 2.0, Duration.ofSeconds(30), 0.0);

            assertThat(p.nextDelay(5)).isEqualTo(Duration.ofSeconds(16));
            assertThat(p.nextDelay(6)).as("would be 32s, capped to 30s").isEqualTo(Duration.ofSeconds(30));
            assertThat(p.nextDelay(9)).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("a multiplier of 1.0 gives fixed-interval retries")
        void fixedInterval() {
            RetryPolicy p = policy(5, Duration.ofSeconds(5), 1.0, Duration.ofMinutes(1), 0.0);

            assertThat(p.nextDelay(2)).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.nextDelay(4)).isEqualTo(Duration.ofSeconds(5));
        }
    }

    @Nested
    @DisplayName("jitter")
    class Jitter {

        /**
         * If a provider has a 30-second outage, every notification that failed
         * during it would otherwise retry at the same instant — a thundering
         * herd that can knock the provider over again just as it recovers.
         */
        @Test
        @DisplayName("spreads delays around the computed value")
        void spreadsDelays() {
            RetryPolicy p = policy(5, Duration.ofSeconds(10), 2.0, Duration.ofMinutes(5), 0.2);

            var delays = new java.util.HashSet<Long>();
            for (int i = 0; i < 200; i++) {
                delays.add(p.nextDelay(2).toMillis());
            }

            assertThat(delays)
                    .as("200 identical delays would mean jitter is not applied")
                    .hasSizeGreaterThan(50);
        }

        @Test
        @DisplayName("stays within the configured band")
        void staysWithinBand() {
            RetryPolicy p = policy(5, Duration.ofSeconds(10), 2.0, Duration.ofMinutes(5), 0.2);

            for (int i = 0; i < 500; i++) {
                long millis = p.nextDelay(2).toMillis();
                assertThat(millis).isBetween(8_000L, 12_000L);   // 10s ±20%
            }
        }

        /**
         * Capping after jitter would let jitter push the delay back above the
         * configured maximum — at which point it is not a maximum.
         */
        @Test
        @DisplayName("the cap is applied before jitter, so jitter cannot exceed it")
        void capAppliedBeforeJitter() {
            RetryPolicy p = policy(10, Duration.ofSeconds(2), 2.0, Duration.ofSeconds(10), 0.2);

            for (int i = 0; i < 500; i++) {
                // Attempt 9 would be 512s uncapped; capped to 10s, then ±20%.
                assertThat(p.nextDelay(9).toMillis()).isBetween(8_000L, 12_000L);
            }
        }

        @Test
        @DisplayName("zero jitter gives exact, repeatable delays")
        void zeroJitterIsDeterministic() {
            RetryPolicy p = policy(5, Duration.ofSeconds(7), 2.0, Duration.ofMinutes(5), 0.0);

            for (int i = 0; i < 50; i++) {
                assertThat(p.nextDelay(2)).isEqualTo(Duration.ofSeconds(7));
            }
        }

        @Test
        @DisplayName("a delay is never negative, even with extreme jitter")
        void neverNegative() {
            RetryPolicy p = policy(5, Duration.ofMillis(10), 2.0, Duration.ofSeconds(1), 1.5);

            for (int i = 0; i < 500; i++) {
                assertThat(p.nextDelay(2).toMillis()).isNotNegative();
            }
        }
    }
}
