package com.bhavesh.notification.idempotency.repository;

import com.bhavesh.notification.idempotency.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/** Data access for remembered responses. */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    /** Hits {@code uq_idempotency_user_endpoint_key}, the index that defines the key's scope. */
    Optional<IdempotencyRecord> findByUserIdAndEndpointAndIdempotencyKey(
            Long userId, String endpoint, String idempotencyKey);

    /**
     * Housekeeping. Keys are only useful for as long as a client might retry;
     * keeping them forever means an ever-growing table and an ever-growing
     * index. Not scheduled in this project — exposed so an operator or a future
     * job can call it — and said plainly rather than implied by an absent
     * method.
     */
    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
