package com.bhavesh.notification.deadletter.repository;

import com.bhavesh.notification.deadletter.domain.DeadLetterEvent;
import com.bhavesh.notification.deadletter.domain.DeadLetterStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Data access for dead-lettered Kafka messages. */
public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {

    /**
     * The operator's backlog query. Hits the partial index
     * {@code idx_dlq_unresolved}, which covers only UNRESOLVED rows — resolved
     * ones are history and would be dead weight.
     */
    Page<DeadLetterEvent> findByStatusOrderByCreatedAtDesc(DeadLetterStatus status, Pageable pageable);

    Page<DeadLetterEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * How many messages the pipeline could not process.
     *
     * <p>Exposed as a gauge, and it is the one to alert on. A non-zero value
     * means something is wrong with <em>this service</em> — unlike a failed
     * notification, which usually means something is wrong with a provider.
     */
    long countByStatus(DeadLetterStatus status);

    /** Whether a notification has already been dead-lettered, to avoid duplicating the record. */
    boolean existsByNotificationIdAndStatus(UUID notificationId, DeadLetterStatus status);
}
