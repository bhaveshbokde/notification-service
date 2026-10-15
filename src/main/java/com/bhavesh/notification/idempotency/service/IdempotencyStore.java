package com.bhavesh.notification.idempotency.service;

import com.bhavesh.notification.idempotency.domain.IdempotencyRecord;
import com.bhavesh.notification.idempotency.repository.IdempotencyRecordRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The transactional primitives behind {@link IdempotencyService}.
 *
 * <p>Separate class, and that is not an accident. Every method here needs
 * {@code REQUIRES_NEW}, and {@code @Transactional} is implemented with a proxy:
 * a call from one method of a bean to another method of the <em>same</em> bean
 * goes straight to {@code this}, bypassing the proxy entirely, so the
 * annotation would be silently ignored. Spring cannot warn about it; the code
 * simply does not do what it says. Putting these on a different bean means the
 * call goes through the proxy and the new transaction actually starts.
 *
 * <h2>Why a separate transaction is required</h2>
 * Claiming a key must become visible to <em>other</em> transactions
 * immediately, before the work it guards has finished. If the claim were part
 * of the caller's transaction, a concurrent duplicate request would not see it
 * — uncommitted rows are invisible under READ COMMITTED — and both requests
 * would proceed to create a notification. The claim commits on its own so the
 * second request collides with it at once.
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRecordRepository repository;

    public IdempotencyStore(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(Long userId, String endpoint, String key) {
        return repository.findByUserIdAndEndpointAndIdempotencyKey(userId, endpoint, key);
    }

    /**
     * Claims a key by inserting its row, committing immediately.
     *
     * <h2>Why this does NOT catch the constraint violation</h2>
     * The obvious implementation wraps the insert in a try/catch and returns
     * {@code Optional.empty()} on a duplicate — keeping the race on the
     * ordinary control-flow path, which reads better. It does not work, and the
     * failure is instructive.
     *
     * <p>When the insert violates {@code uq_idempotency_user_endpoint_key}, the
     * transaction is marked <strong>rollback-only</strong>. Catching the
     * exception does not un-mark it: the mark lives on the transaction, not on
     * the exception. So the method returns normally, Spring's interceptor tries
     * to commit, and the commit fails with
     * {@code UnexpectedRollbackException: Transaction silently rolled back
     * because it has been marked as rollback-only} — which escapes as a 500.
     *
     * <p>Under concurrency that turned ten of twelve simultaneous requests into
     * server errors while the index was working perfectly. Found by
     * {@code IdempotencyConcurrencyIT}; every single-threaded test passed
     * throughout, because single-threaded there is no race to lose.
     *
     * <p>So the exception is allowed to propagate: the inner transaction rolls
     * back cleanly, and {@link IdempotencyService} — which runs <em>outside</em>
     * this transaction — catches it and decides what to do.
     *
     * @throws DataIntegrityViolationException if the key is already claimed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord claim(Long userId, String endpoint, String key, String requestHash) {
        return repository.saveAndFlush(new IdempotencyRecord(userId, key, endpoint, requestHash));
    }

    /** Stores the response to replay, in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResponse(Long recordId, UUID notificationId, int status, String body) {
        repository.findById(recordId).ifPresent(record -> {
            record.recordResponse(notificationId, status, body);
            repository.save(record);
        });
    }

    /**
     * Drops a claim whose work failed, so the client may genuinely retry.
     *
     * <p>Without this, a request that died on a transient fault would leave its
     * key permanently claimed with no stored response, and every retry would be
     * told "that key is still in flight" forever.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseClaim(Long recordId) {
        repository.deleteById(recordId);
    }
}
