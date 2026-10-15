package com.bhavesh.notification.notification.repository;

import com.bhavesh.notification.notification.domain.AttemptOutcome;
import com.bhavesh.notification.notification.domain.NotificationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Data access for the append-only delivery attempt history. */
public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, Long> {

    /** The full history for one notification, oldest first — the order an operator reads it in. */
    List<NotificationAttempt> findByNotificationIdOrderByAttemptNumberAsc(UUID notificationId);

    /**
     * Whether this attempt number has already been recorded.
     *
     * <p>Used as a cheap pre-check by the delivery processor. It is only an
     * optimisation: {@code uq_attempts_notification_number} is the actual
     * guarantee, because this read and the subsequent insert are not atomic and
     * two concurrent consumers could both pass it.
     */
    boolean existsByNotificationIdAndAttemptNumber(UUID notificationId, int attemptNumber);

    long countByNotificationId(UUID notificationId);

    long countByOutcome(AttemptOutcome outcome);

    /**
     * Mean provider latency per outcome, over recent attempts.
     *
     * <p>Exposed for diagnostics rather than as a metric — Micrometer timers
     * already capture this live. It answers the retrospective question: "was
     * the provider slow at 03:00, and for which kind of outcome?"
     */
    @Query("""
            SELECT a.outcome, AVG(a.durationMs), COUNT(a)
              FROM NotificationAttempt a
             WHERE a.durationMs IS NOT NULL
             GROUP BY a.outcome
            """)
    List<Object[]> averageDurationByOutcome();

    /** Every attempt for a set of notifications — one query, not one per notification. */
    @Query("""
            SELECT a FROM NotificationAttempt a
             WHERE a.notificationId IN :notificationIds
             ORDER BY a.notificationId, a.attemptNumber
            """)
    List<NotificationAttempt> findForNotifications(@Param("notificationIds") List<UUID> notificationIds);
}
