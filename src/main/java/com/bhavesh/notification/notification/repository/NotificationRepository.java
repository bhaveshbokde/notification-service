package com.bhavesh.notification.notification.repository;

import com.bhavesh.notification.notification.domain.Notification;
import com.bhavesh.notification.notification.domain.NotificationChannel;
import com.bhavesh.notification.notification.domain.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Notification}.
 *
 * <p>Every caller-facing finder takes a {@code userId} and puts it in the WHERE
 * clause. That is what makes data isolation structural: a service cannot
 * accidentally hand back another caller's notification, because there is no
 * method that would return one. Loading by id and then comparing owners in Java
 * is the same check written somewhere it can be forgotten.
 *
 * <p>A miss therefore means "not found or not yours", and the service answers
 * 404 either way — see {@link com.bhavesh.notification.common.exception.ResourceNotFoundException}.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Ownership-scoped lookup. The caller-facing detail endpoint uses this one. */
    Optional<Notification> findByIdAndCreatedBy(UUID id, Long createdBy);

    /**
     * Unscoped lookup, for administrators and for the Kafka consumer.
     *
     * <p>The consumer legitimately needs this: it processes events on behalf of
     * whoever submitted them, and has no caller identity of its own.
     */
    @Query("SELECT n FROM Notification n WHERE n.id = :id")
    Optional<Notification> findByIdUnscoped(@Param("id") UUID id);

    /**
     * The caller's notification history, with optional filters.
     *
     * <p>Every filter is written as {@code (:param IS NULL OR <predicate>)} so
     * one query serves all combinations. The alternative — a
     * {@code Specification} or a hand-built Criteria tree — buys flexibility
     * this endpoint does not need and costs readability it does.
     *
     * <p>Parameters are bound by JPA, never concatenated, so a value such as
     * {@code "' OR 1=1 --"} is compared as a literal string.
     *
     * <p>Hits {@code idx_notifications_creator_created}. The {@code id}
     * tiebreaker makes the ordering total: without it two notifications sharing
     * a {@code created_at} could appear on two pages or on neither, because the
     * database is free to order ties differently between queries.
     */
    @Query(value = """
            SELECT n FROM Notification n
             WHERE n.createdBy = :userId
               AND (:status  IS NULL OR n.status  = :status)
               AND (:channel IS NULL OR n.channel = :channel)
               AND (CAST(:createdAfter  AS timestamp) IS NULL OR n.createdAt >= :createdAfter)
               AND (CAST(:createdBefore AS timestamp) IS NULL OR n.createdAt <= :createdBefore)
             ORDER BY n.createdAt DESC, n.id DESC
            """,
            countQuery = """
            SELECT COUNT(n) FROM Notification n
             WHERE n.createdBy = :userId
               AND (:status  IS NULL OR n.status  = :status)
               AND (:channel IS NULL OR n.channel = :channel)
               AND (CAST(:createdAfter  AS timestamp) IS NULL OR n.createdAt >= :createdAfter)
               AND (CAST(:createdBefore AS timestamp) IS NULL OR n.createdAt <= :createdBefore)
            """)
    Page<Notification> search(@Param("userId") Long userId,
                              @Param("status") NotificationStatus status,
                              @Param("channel") NotificationChannel channel,
                              @Param("createdAfter") Instant createdAfter,
                              @Param("createdBefore") Instant createdBefore,
                              Pageable pageable);

    /** Administrative listing: every caller's notifications. */
    @Query(value = """
            SELECT n FROM Notification n
             WHERE (:status  IS NULL OR n.status  = :status)
               AND (:channel IS NULL OR n.channel = :channel)
               AND (CAST(:createdAfter  AS timestamp) IS NULL OR n.createdAt >= :createdAfter)
               AND (CAST(:createdBefore AS timestamp) IS NULL OR n.createdAt <= :createdBefore)
             ORDER BY n.createdAt DESC, n.id DESC
            """,
            countQuery = """
            SELECT COUNT(n) FROM Notification n
             WHERE (:status  IS NULL OR n.status  = :status)
               AND (:channel IS NULL OR n.channel = :channel)
               AND (CAST(:createdAfter  AS timestamp) IS NULL OR n.createdAt >= :createdAfter)
               AND (CAST(:createdBefore AS timestamp) IS NULL OR n.createdAt <= :createdBefore)
            """)
    Page<Notification> searchAll(@Param("status") NotificationStatus status,
                                 @Param("channel") NotificationChannel channel,
                                 @Param("createdAfter") Instant createdAfter,
                                 @Param("createdBefore") Instant createdBefore,
                                 Pageable pageable);

    /**
     * Notifications whose scheduled retry is now due.
     *
     * <p>Hits the partial index {@code idx_notifications_retry_due}, which
     * covers only {@code RETRY_SCHEDULED} rows — the vast majority of the table
     * is terminal and would be dead weight in a full index.
     *
     * <p>This is a safety net, not the primary retry path. Retries normally
     * flow through the Kafka retry topic; this sweeper catches notifications
     * whose retry event was lost, which should be rare and is worth a metric.
     */
    @Query("""
            SELECT n FROM Notification n
             WHERE n.status = 'RETRY_SCHEDULED'
               AND n.nextAttemptAt <= :now
             ORDER BY n.nextAttemptAt ASC
            """)
    List<Notification> findDueForRetry(@Param("now") Instant now, Pageable pageable);

    /**
     * Notifications stuck in PROCESSING for too long.
     *
     * <p>A consumer that died mid-delivery leaves a row here, and nothing else
     * will ever move it: PROCESSING is not claimable, so no retry event can
     * rescue it. Surfacing them as a gauge makes "why is this notification
     * still pending?" answerable.
     */
    @Query("""
            SELECT COUNT(n) FROM Notification n
             WHERE n.status = 'PROCESSING'
               AND n.updatedAt < :stuckBefore
            """)
    long countStuckInProcessing(@Param("stuckBefore") Instant stuckBefore);

    long countByStatus(NotificationStatus status);

    long countByCreatedBy(Long createdBy);
}
