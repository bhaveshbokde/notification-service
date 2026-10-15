package com.bhavesh.notification.idempotency.service;

import com.bhavesh.notification.common.exception.ApiException;
import com.bhavesh.notification.common.exception.ErrorCode;
import com.bhavesh.notification.idempotency.domain.IdempotencyRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Makes a non-idempotent POST safe to retry.
 *
 * <h2>The problem</h2>
 * A client sends {@code POST /notifications}, the server creates it, and the
 * response is lost to a dropped connection. The client has no way to tell "it
 * never arrived" from "it worked and I missed the answer", so it retries — and
 * a second notification is sent. Retrying is the correct behaviour for the
 * client; producing a duplicate is a defect in the server.
 *
 * <p>For a notification service this matters more than for most APIs: the
 * duplicate is not a harmless extra row, it is a second SMS to a real phone, or
 * a second password-reset email that invalidates the first one's link.
 *
 * <h2>The mechanism</h2>
 * The client sends an {@code Idempotency-Key} header. The first request claims
 * it; the work runs; the response is stored. A retry with the same key finds
 * the stored response and replays it verbatim — same status, same body — so
 * the retry is indistinguishable from the original call. That is the whole
 * point: returning a bare 409 would be correct about "I have seen this" and
 * useless to the client that lost the first response.
 *
 * <h2>Three outcomes, not two</h2>
 * <ul>
 *   <li><strong>Replay</strong> — same key, same request body, stored response
 *       exists.</li>
 *   <li><strong>Key reused with a different body</strong> — 409. The key says
 *       "this is the same operation" and the body says otherwise; one of the
 *       two is a client bug, and silently replaying the first response would
 *       swallow a genuine second notification.</li>
 *   <li><strong>Still in flight</strong> — the key is claimed but has no
 *       response yet, so the original request is still running. 409, with a
 *       retry hint. Waiting on it would tie up a thread; proceeding would
 *       duplicate the work the key exists to prevent.</li>
 * </ul>
 *
 * <h2>Why the database and not Redis</h2>
 * The claim has to be atomic with respect to other application instances, and
 * it has to survive a restart. A unique index gives both for free. Redis could
 * do it with {@code SET key value NX}, but then the idempotency guarantee —
 * which is a correctness property — would depend on a cache whose eviction
 * policy is tuned for something else entirely, and a Redis outage would
 * silently start permitting duplicate sends rather than merely slowing things
 * down.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs {@code action} at most once per key.
     *
     * @param userId       scopes the key, so two callers may use the same string
     * @param endpoint     scopes it further: the same key on two endpoints is two operations
     * @param key          the client's {@code Idempotency-Key}; null disables the
     *                     mechanism and runs the action directly
     * @param requestBody  canonical representation of the request, hashed to detect reuse
     * @param responseType the type to deserialise a replayed response into
     * @param idExtractor  pulls the notification id out of the result, so the
     *                     record can point at what it created
     * @param action       the work to perform, exactly once
     */
    public <T> Result<T> execute(Long userId, String endpoint, String key, String requestBody,
                                 Class<T> responseType,
                                 java.util.function.Function<T, UUID> idExtractor,
                                 Supplier<T> action) {

        if (key == null || key.isBlank()) {
            // The header is optional. A client that does not send one accepts
            // that a retry may duplicate the notification — their choice, made
            // explicit rather than silently taken for them.
            return new Result<>(action.get(), false);
        }

        String requestHash = IdempotencyRecord.hash(requestBody);

        Optional<IdempotencyRecord> existing = store.find(userId, endpoint, key);
        if (existing.isPresent()) {
            return replayOrReject(existing.get(), requestHash, responseType);
        }

        // Claim the key in its own transaction so a concurrent duplicate sees
        // it immediately. Losing the race means someone else claimed it between
        // our read and our insert — re-read and take the same path as above.
        //
        // The catch is HERE rather than inside the store, and that placement is
        // load-bearing: catching it inside the @Transactional(REQUIRES_NEW)
        // method would leave that transaction marked rollback-only, and its
        // commit would then fail with UnexpectedRollbackException. This code
        // runs outside that transaction, so by the time it executes the inner
        // one has already rolled back cleanly.
        IdempotencyRecord record;
        try {
            record = store.claim(userId, endpoint, key, requestHash);
        } catch (DataIntegrityViolationException lostTheRace) {
            IdempotencyRecord winner = store.find(userId, endpoint, key)
                    .orElseThrow(() -> new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                            "Idempotency key is being processed; retry shortly"));
            return replayOrReject(winner, requestHash, responseType);
        }
        T result;
        try {
            result = action.get();
        } catch (RuntimeException failure) {
            // Release the claim so a genuine retry is possible. Without this the
            // key would stay claimed with no response and every retry would be
            // told the original is still in flight — forever.
            store.releaseClaim(record.getId());
            throw failure;
        }

        store.recordResponse(record.getId(), idExtractor.apply(result), 202, serialize(result));
        return new Result<>(result, false);
    }

    private <T> Result<T> replayOrReject(IdempotencyRecord record, String requestHash,
                                         Class<T> responseType) {
        if (!record.matches(requestHash)) {
            log.warn("Idempotency key reused with a different body: endpoint={} user={}",
                    record.getEndpoint(), record.getUserId());
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request body");
        }

        if (!record.hasResponse()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                    "The original request with this Idempotency-Key is still in progress; retry shortly");
        }

        log.info("Replaying stored response for endpoint={} user={} notification={}",
                record.getEndpoint(), record.getUserId(), record.getNotificationId());
        return new Result<>(deserialize(record.getResponseBody(), responseType), true);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Not fatal to the operation, which already succeeded — but it does
            // mean a retry will re-run the work, so it must be visible.
            log.error("Could not serialise response for replay; retries will re-execute", e);
            return null;
        }
    }

    private <T> T deserialize(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "Stored idempotent response could not be read", e);
        }
    }

    /**
     * @param value    the result, whether freshly computed or replayed
     * @param replayed true when this came from a stored response — the
     *                 controller uses it to answer 200 instead of 202, since
     *                 nothing was accepted for processing this time
     */
    public record Result<T>(T value, boolean replayed) {
    }
}
