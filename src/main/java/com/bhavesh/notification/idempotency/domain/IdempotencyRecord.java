package com.bhavesh.notification.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * A remembered response, so a replayed request returns the original answer
 * rather than performing the work twice.
 *
 * <p>Maps to {@code idempotency_records} (V3). Written once and never updated
 * except to attach the response, so it carries {@code created_at} alone.
 *
 * <h2>The problem</h2>
 * A client sends {@code POST /notifications}, the server creates it, and the
 * response is lost to a dropped connection. The client cannot tell "it never
 * arrived" from "it worked and I missed the answer", so it retries — and a
 * second notification goes out. Retrying is correct client behaviour;
 * producing a duplicate is a server defect.
 *
 * <h2>Why a request hash as well as a key</h2>
 * The key alone cannot tell a genuine retry from a client bug. If someone sends
 * {@code Idempotency-Key: abc} with a password-reset email and then reuses
 * {@code abc} for a marketing message, replaying the first response would
 * silently swallow the second request. Comparing a SHA-256 of the canonical
 * request turns that into an explicit 409.
 *
 * <p>SHA-256 rather than a slow hash: the input is a request body, not a
 * password, so there is nothing to brute-force and the only requirement is a
 * low collision probability.
 *
 * <h2>Why the response is stored</h2>
 * A retry must be indistinguishable from the original call, including the
 * status code and the body. Returning a bare 409 would be correct about "I have
 * seen this" and useless to a client that lost the first response — which is
 * the exact situation idempotency exists to serve.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Scopes the key. Two callers must be able to choose the same key without
     * colliding; a global key space would let one caller's choice of
     * {@code "1"} block everyone else's.
     */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    /** Scopes it further: the same key on two endpoints is two operations. */
    @Column(name = "endpoint", nullable = false, length = 100, updatable = false)
    private String endpoint;

    /** Lowercase hex SHA-256, matching {@code ck_idempotency_hash}. */
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "notification_id")
    private UUID notificationId;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(Long userId, String idempotencyKey, String endpoint, String requestHash) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
    }

    /** Lowercase hex SHA-256 of a UTF-8 payload. */
    public static String hash(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated for every conforming JRE; unreachable.
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }

    public void recordResponse(UUID notificationId, int status, String body) {
        this.notificationId = notificationId;
        this.responseStatus = status;
        this.responseBody = body;
    }

    /**
     * False while the original request is still in flight — there is nothing to
     * replay yet, and the caller should be told to retry rather than be given a
     * half-written answer.
     */
    public boolean hasResponse() {
        return responseStatus != null;
    }

    public boolean matches(String otherRequestHash) {
        return requestHash.equals(otherRequestHash);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IdempotencyRecord that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(IdempotencyRecord.class);
    }

    /** Omits the key (client-chosen, possibly guessable) and the stored body. */
    @Override
    public String toString() {
        return "IdempotencyRecord{id=%d, userId=%d, endpoint='%s', status=%s}"
                .formatted(id, userId, endpoint, responseStatus);
    }
}
