package com.bhavesh.notification.idempotency;

import com.bhavesh.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Idempotency under genuine concurrency.
 *
 * <p><strong>This is the test a mock cannot replace.</strong>
 * {@code NotificationServiceTest} proves the service consults the store; only
 * real threads against a real database can prove that the unique index
 * actually arbitrates the race — and that the losers get a usable answer rather
 * than a 500.
 *
 * <p>The stakes are higher here than for a typical API. A duplicate is not an
 * extra row: it is a second SMS to a real phone, or a second password-reset
 * email that invalidates the first one's link.
 *
 * <h2>Why a CyclicBarrier</h2>
 * Threads started in a loop do not run simultaneously — the first may finish
 * before the last begins, and the test would pass without ever creating the
 * race it claims to test. The barrier holds every thread until all have
 * arrived, then releases them together.
 */
@Tag("concurrency")
class IdempotencyConcurrencyIT extends AbstractIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 12;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        token = userToken("concurrency-caller");
    }

    private static final String BODY = """
            {"channel":"IN_APP","recipient":"concurrent-user","body":"one message only"}""";

    /**
     * Fires {@code count} identical requests simultaneously and returns their
     * status codes.
     */
    private List<Integer> fireSimultaneously(int count, String idempotencyKey) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(count);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Integer>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await(30, TimeUnit.SECONDS);
                    var request = post("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY);
                    if (idempotencyKey != null) {
                        request = request.header("Idempotency-Key", idempotencyKey);
                    }
                    return mockMvc.perform(request).andReturn().getResponse().getStatus();
                }));
            }
            List<Integer> statuses = new ArrayList<>(count);
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The headline guarantee: twelve simultaneous requests carrying the same
     * key produce exactly one notification.
     */
    @Test
    @Timeout(120)
    @DisplayName("concurrent requests with the same key create exactly ONE notification")
    void sameKeyCreatesOneNotification() throws Exception {
        List<Integer> statuses = fireSimultaneously(CONCURRENT_REQUESTS, "concurrent-key");

        assertThat(countRows("notifications"))
                .as("a duplicate here is a second real SMS or email, not just an extra row")
                .isEqualTo(1);

        assertThat(countRows("outbox_events"))
                .as("and exactly one event, so it is delivered once")
                .isEqualTo(1);

        assertThat(statuses)
                .as("every caller must get a usable answer — a 500 means the race broke something")
                .allMatch(s -> s == 202 || s == 200 || s == 409);

        assertThat(statuses.stream().filter(s -> s == 202).count())
                .as("exactly one request did the work")
                .isEqualTo(1);
    }

    /**
     * The losers must be told something actionable. A 500 would be correct
     * about "something went wrong" and useless — the client cannot tell whether
     * to retry.
     */
    @Test
    @Timeout(120)
    @DisplayName("every loser gets a clean 200 or 409, never a 500")
    void losersGetActionableResponses() throws Exception {
        List<Integer> statuses = fireSimultaneously(CONCURRENT_REQUESTS, "loser-key");

        assertThat(statuses).doesNotContain(500);

        long replayed = statuses.stream().filter(s -> s == 200).count();
        long inFlight = statuses.stream().filter(s -> s == 409).count();

        assertThat(replayed + inFlight)
                .as("everyone except the winner is either replayed or told to retry")
                .isEqualTo(CONCURRENT_REQUESTS - 1);
    }

    /**
     * The control case. Without a key the caller has explicitly accepted that a
     * retry may duplicate — so twelve concurrent requests genuinely are twelve
     * notifications, and that is correct rather than a bug.
     */
    @Test
    @Timeout(120)
    @DisplayName("without a key, concurrent requests each create a notification")
    void noKeyMeansNoDeduplication() throws Exception {
        List<Integer> statuses = fireSimultaneously(CONCURRENT_REQUESTS, null);

        assertThat(statuses).allMatch(s -> s == 202);
        assertThat(countRows("notifications")).isEqualTo(CONCURRENT_REQUESTS);
    }

    /**
     * Two callers must be able to choose the same key without colliding. A
     * global key space would let one caller's choice of {@code "1"} block
     * everyone else's.
     */
    @Test
    @Timeout(120)
    @DisplayName("the same key from different callers does not collide")
    void keysAreScopedPerCallerUnderConcurrency() throws Exception {
        String otherToken = userToken("other-concurrent-caller");

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> {
                startLine.await(30, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/notifications")
                                .header(HttpHeaders.AUTHORIZATION, token)
                                .header("Idempotency-Key", "shared-string")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> second = pool.submit(() -> {
                startLine.await(30, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/notifications")
                                .header(HttpHeaders.AUTHORIZATION, otherToken)
                                .header("Idempotency-Key", "shared-string")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY))
                        .andReturn().getResponse().getStatus();
            });

            assertThat(first.get(60, TimeUnit.SECONDS)).isEqualTo(202);
            assertThat(second.get(60, TimeUnit.SECONDS)).isEqualTo(202);
        } finally {
            pool.shutdownNow();
        }

        assertThat(countRows("notifications"))
                .as("two different callers, two notifications")
                .isEqualTo(2);
    }

    /**
     * The database constraint is the last line of defence, below the
     * application's own pre-read. This bypasses the service entirely.
     */
    @Test
    @DisplayName("the unique index refuses a duplicate key even for a direct insert")
    void uniqueIndexIsTheBackstop() {
        Long userId = userRepository.findByUsernameIgnoreCase("concurrency-caller")
                .orElseThrow().getId();

        transactionTemplate.executeWithoutResult(status -> entityManager.createNativeQuery("""
                        INSERT INTO idempotency_records (user_id, idempotency_key, endpoint, request_hash)
                        VALUES (:uid, 'direct-key', 'POST /api/v1/notifications', :hash)
                        """)
                .setParameter("uid", userId)
                .setParameter("hash", "a".repeat(64))
                .executeUpdate());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        transactionTemplate.executeWithoutResult(status -> entityManager.createNativeQuery("""
                                        INSERT INTO idempotency_records (user_id, idempotency_key, endpoint, request_hash)
                                        VALUES (:uid, 'direct-key', 'POST /api/v1/notifications', :hash)
                                        """)
                                .setParameter("uid", userId)
                                .setParameter("hash", "b".repeat(64))
                                .executeUpdate()))
                .hasMessageContaining("uq_idempotency_user_endpoint_key");
    }
}
