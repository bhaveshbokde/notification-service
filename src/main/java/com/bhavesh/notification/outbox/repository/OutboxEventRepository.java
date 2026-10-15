package com.bhavesh.notification.outbox.repository;

import com.bhavesh.notification.outbox.domain.OutboxEvent;
import com.bhavesh.notification.outbox.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Data access for the transactional outbox.
 *
 * <p>The claiming query is the only genuinely subtle thing in this repository,
 * and it is worth reading carefully because the whole publisher design rests on
 * it.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Atomically claims a batch of publishable events for one publisher.
     *
     * <pre>{@code
     * SELECT ... FROM outbox_events
     *  WHERE status = 'PENDING'
     *  ORDER BY priority, created_at
     *  LIMIT :batchSize
     *  FOR UPDATE SKIP LOCKED
     * }</pre>
     *
     * <h2>Why {@code FOR UPDATE SKIP LOCKED}</h2>
     * {@code FOR UPDATE} alone would make a second publisher <em>block</em> on
     * the rows the first one is holding, so two publishers would serialise into
     * one and the second would contribute nothing but a held connection.
     * {@code SKIP LOCKED} tells PostgreSQL to step over locked rows and return
     * the next unlocked ones instead — so each publisher gets a disjoint batch
     * and they genuinely run in parallel.
     *
     * <p>This is the standard queue-claiming primitive and it is the reason the
     * publisher can be scaled horizontally without any coordination, leader
     * election or distributed lock.
     *
     * <h2>Why the rows are then stamped PUBLISHING</h2>
     * The row lock only lasts for the claiming transaction. Once that commits,
     * another publisher could pick the same row up again while the first is
     * still mid-send. Stamping the status and {@code claimed_at} inside the
     * claiming transaction is what carries the claim beyond the lock's
     * lifetime, and the lease timeout is what releases it if the publisher dies.
     *
     * <h2>Why native SQL</h2>
     * JPQL has no portable way to express {@code SKIP LOCKED}.
     * {@code @Lock(PESSIMISTIC_WRITE)} with a timeout hint comes close but
     * generates {@code NOWAIT} or a plain wait depending on the dialect, and
     * the distinction is exactly what matters here. The guarantee is a
     * PostgreSQL behaviour, so the statement is PostgreSQL.
     *
     * <p>Must be called inside a transaction — the lock is released at commit,
     * and {@code SKIP LOCKED} outside one is meaningless.
     */
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE status = 'PENDING'
             ORDER BY created_at
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimBatch(@Param("batchSize") int batchSize);

    /**
     * Returns rows whose publisher claim has expired back to PENDING.
     *
     * <p>Recovers from a publisher that died mid-batch: without this those rows
     * would sit in PUBLISHING forever and their notifications would never be
     * delivered. The lease must be longer than the worst-case time to publish a
     * batch, or this will steal rows from a publisher that is merely slow and
     * the event will be sent twice.
     *
     * <p>Returns the number of rows recovered so the publisher can log it —
     * a non-zero value here means an instance died, which is worth knowing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_events
               SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL
             WHERE status = 'PUBLISHING'
               AND claimed_at < :expiredBefore
            """, nativeQuery = true)
    int releaseExpiredClaims(@Param("expiredBefore") Instant expiredBefore);

    /**
     * How many events are waiting to be published.
     *
     * <p>Exposed as a gauge. A rising backlog is the earliest signal that
     * either the broker is unreachable or the publisher has stopped, and it
     * appears long before anyone notices that notifications are not arriving.
     */
    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.status IN ('PENDING', 'PUBLISHING')")
    long countBacklog();

    long countByStatus(OutboxStatus status);

    /** The oldest unpublished event; its age is the publisher's real lag. */
    @Query(value = """
            SELECT MIN(created_at) FROM outbox_events
             WHERE status IN ('PENDING', 'PUBLISHING')
            """, nativeQuery = true)
    Instant oldestUnpublishedAt();

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);

    /** Operator view: events that gave up entirely and need attention. */
    List<OutboxEvent> findByStatusOrderByCreatedAtDesc(OutboxStatus status);

    /**
     * Housekeeping. Published rows are dead weight once nobody will ask about
     * them again; without pruning, the table and its indexes grow forever.
     * Not scheduled by default — deleting history is a decision an operator
     * should make deliberately — so it is exposed rather than invoked.
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = 'PUBLISHED' AND e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
