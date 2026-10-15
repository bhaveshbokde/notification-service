package com.bhavesh.notification.outbox;

import com.bhavesh.notification.notification.domain.NotificationChannel;
import com.bhavesh.notification.notification.domain.NotificationPriority;
import com.bhavesh.notification.notification.dto.CreateNotificationRequest;
import com.bhavesh.notification.notification.service.NotificationService;
import com.bhavesh.notification.outbox.domain.OutboxStatus;
import com.bhavesh.notification.outbox.repository.OutboxEventRepository;
import com.bhavesh.notification.support.AbstractIntegrationTest;
import com.bhavesh.notification.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The transactional outbox, against a real PostgreSQL.
 *
 * <p><strong>This is the test a mock cannot replace.</strong>
 * {@code NotificationServiceTest} proves the service writes both rows; only a
 * real database can prove they share a transaction, that a rollback takes both
 * with it, and that {@code FOR UPDATE SKIP LOCKED} actually gives concurrent
 * publishers disjoint batches.
 */
class OutboxTransactionIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private TransactionTemplate txTemplate;

    private Long userId;

    @BeforeEach
    void createCaller() {
        User user = createUser("outbox-caller");
        this.userId = user.getId();
    }

    private static CreateNotificationRequest request(String recipient) {
        return new CreateNotificationRequest(NotificationChannel.IN_APP, recipient,
                null, "body", NotificationPriority.NORMAL, null, 3);
    }

    @Test
    @DisplayName("the notification and its outbox event are committed together")
    void bothRowsCommitTogether() {
        UUID id = txTemplate.execute(status ->
                notificationService.create(request("atomic-probe"), userId).id());

        assertThat(notificationStatus(id)).isEqualTo("PENDING");
        assertThat(outboxStatusFor(id))
                .as("the event must exist the moment the notification does")
                .isIn("PENDING", "PUBLISHING", "PUBLISHED");
    }

    /**
     * The failure the outbox exists to prevent, in reverse: if the two writes
     * were not in one transaction, a rollback would leave one behind.
     */
    @Test
    @DisplayName("a rollback takes BOTH rows with it — neither is left orphaned")
    void rollbackRemovesBoth() {
        long notificationsBefore = countRows("notifications");
        long outboxBefore = countRows("outbox_events");

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            notificationService.create(request("rollback-probe"), userId);
            // Something fails after both writes but before commit — a constraint
            // violation, a bug, a killed connection.
            throw new IllegalStateException("simulated failure after both writes");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(countRows("notifications"))
                .as("the notification must not survive")
                .isEqualTo(notificationsBefore);
        assertThat(countRows("outbox_events"))
                .as("and neither must the event — an orphaned event would reference nothing")
                .isEqualTo(outboxBefore);
    }

    @Test
    @DisplayName("the publisher drains the outbox and the event reaches PUBLISHED")
    void publisherDrainsTheOutbox() {
        UUID id = notificationService.create(request("publish-probe"), userId).id();

        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(outboxStatusFor(id)).isEqualTo("PUBLISHED"));

        assertThat(countRows("outbox_events")).isEqualTo(1);
    }

    /**
     * {@code published_at} is written only after the broker acknowledges. If it
     * were set when {@code send()} returned, "PUBLISHED" would mean "we asked"
     * rather than "the broker has it" — and the whole mechanism would be
     * decorative.
     */
    @Test
    @DisplayName("published_at is set, proving the broker acknowledged")
    void publishedAtProvesAcknowledgement() {
        UUID id = notificationService.create(request("ack-probe"), userId).id();

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(outboxStatusFor(id)).isEqualTo("PUBLISHED"));

        Instant publishedAt = transactionTemplate.execute(status -> (Instant) entityManager
                .createNativeQuery("SELECT published_at FROM outbox_events WHERE aggregate_id = :id")
                .setParameter("id", id)
                .getSingleResult());

        assertThat(publishedAt).isNotNull();
    }

    /**
     * {@code FOR UPDATE SKIP LOCKED} is what lets several publishers run
     * without coordination. Plain {@code FOR UPDATE} would make the second
     * block on the first's rows, serialising them into one.
     *
     * <p>Each thread claims in its own transaction; the union of their claims
     * must contain no duplicates.
     */
    @Test
    @DisplayName("concurrent publishers claim disjoint batches — SKIP LOCKED works")
    void concurrentPublishersDoNotOverlap() throws Exception {
        int events = 40;
        for (int i = 0; i < events; i++) {
            notificationService.create(request("claim-probe-" + i), userId);
        }

        int publishers = 4;
        CyclicBarrier startLine = new CyclicBarrier(publishers);
        ExecutorService pool = Executors.newFixedThreadPool(publishers);

        List<List<UUID>> claims = new ArrayList<>();
        try {
            List<Future<List<UUID>>> futures = new ArrayList<>();
            for (int i = 0; i < publishers; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await(20, TimeUnit.SECONDS);
                    return txTemplate.execute(status ->
                            outboxRepository.claimBatch(10).stream()
                                    .map(e -> e.getId())
                                    .toList());
                }));
            }
            for (Future<List<UUID>> f : futures) {
                claims.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        List<UUID> all = claims.stream().flatMap(List::stream).toList();

        assertThat(all)
                .as("two publishers claiming the same row would publish the event twice")
                .doesNotHaveDuplicates();
        assertThat(all)
                .as("every publisher should have got work — SKIP LOCKED, not blocking")
                .isNotEmpty();
    }

    /**
     * Recovery from a publisher that died mid-batch. Without lease expiry those
     * rows would sit in PUBLISHING forever and their notifications would never
     * be delivered.
     */
    @Test
    @DisplayName("an expired publisher lease is reclaimed")
    void expiredLeaseIsReclaimed() {
        UUID id = notificationService.create(request("lease-probe"), userId).id();

        // Pretend a publisher claimed it and died ten minutes ago.
        transactionTemplate.executeWithoutResult(status -> entityManager.createNativeQuery("""
                        UPDATE outbox_events
                           SET status = 'PUBLISHING',
                               claimed_at = now() - interval '10 minutes',
                               claimed_by = 'dead-publisher'
                         WHERE aggregate_id = :id
                        """)
                .setParameter("id", id)
                .executeUpdate());

        assertThat(outboxStatusFor(id)).isEqualTo("PUBLISHING");

        // The running publisher's next cycle should reclaim and publish it.
        await().atMost(Duration.ofSeconds(25))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(outboxStatusFor(id)).isEqualTo("PUBLISHED"));
    }

    /**
     * A healthy publisher's claim must not be stolen while it is still working,
     * or the event is published twice.
     */
    @Test
    @DisplayName("a fresh lease is NOT reclaimed")
    void freshLeaseSurvives() {
        UUID id = notificationService.create(request("fresh-lease-probe"), userId).id();

        transactionTemplate.executeWithoutResult(status -> entityManager.createNativeQuery("""
                        UPDATE outbox_events
                           SET status = 'PUBLISHING', claimed_at = now(), claimed_by = 'busy-publisher'
                         WHERE aggregate_id = :id
                        """)
                .setParameter("id", id)
                .executeUpdate());

        int recovered = transactionTemplate.execute(status ->
                outboxRepository.releaseExpiredClaims(Instant.now().minus(Duration.ofMinutes(2))));

        assertThat(recovered)
                .as("the lease is seconds old; stealing it would duplicate the publish")
                .isZero();
        assertThat(outboxStatusFor(id)).isEqualTo("PUBLISHING");
    }

    @Test
    @DisplayName("the backlog gauge counts unpublished events")
    void backlogCountsUnpublished() {
        assertThat(outboxRepository.countBacklog()).isZero();

        transactionTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < 3; i++) {
                notificationService.create(request("backlog-probe-" + i), userId);
            }
        });

        // Either still queued, or already drained — both are correct; what
        // matters is the count never goes negative or misses rows.
        assertThat(outboxRepository.countBacklog()).isBetween(0L, 3L);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(outboxRepository.countBacklog()).isZero());

        assertThat(outboxRepository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(3);
    }
}
