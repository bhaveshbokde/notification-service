package com.bhavesh.notification.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error identifiers returned in every error body.
 *
 * <p>Clients branch on {@code code}; the human-readable {@code detail} may be
 * reworded freely without breaking them. Each constant also carries the HTTP
 * status it maps to, which keeps status selection a property of the error
 * rather than an if-else chain in the exception handler.
 */
public enum ErrorCode {

    // ---- 400 ----
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "One or more fields are invalid"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body could not be parsed"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "A request parameter is invalid"),
    INVALID_RECIPIENT(HttpStatus.BAD_REQUEST, "The recipient is not valid for this channel"),
    INVALID_CHANNEL(HttpStatus.BAD_REQUEST, "The notification channel is not supported"),

    // ---- 401 ----
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Username or password is incorrect"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "The access token has expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "The access token is invalid"),
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "This account is disabled"),

    // ---- 403 ----
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You are not allowed to perform this action"),

    // ---- 404 ----
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource does not exist"),

    // ---- 405 / 415 ----
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported"),

    // ---- 409 ----
    /**
     * The same idempotency key was used with a different request body. Not a
     * replay — one of the two requests is a client bug, and silently replaying
     * the first response would swallow the second request entirely.
     */
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT,
            "This Idempotency-Key was already used with a different request"),
    /** A concurrent request holds the same key, or an optimistic lock was lost. */
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT,
            "The request conflicts with one already in progress; retry shortly"),
    DUPLICATE_USERNAME(HttpStatus.CONFLICT, "That username is already taken"),

    // ---- 422 ----
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "The request violates a business rule"),
    INVALID_STATUS_TRANSITION(HttpStatus.UNPROCESSABLE_ENTITY,
            "That notification status transition is not allowed"),
    NOTIFICATION_NOT_REPLAYABLE(HttpStatus.UNPROCESSABLE_ENTITY,
            "This dead-lettered event cannot be replayed in its current state"),

    // ---- 429 ----
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS,
            "Too many requests; slow down and retry after the window resets"),

    // ---- 500 / 503 ----
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"),
    /** Kafka, Redis or the database is unreachable. */
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A required downstream service is unavailable");

    private final HttpStatus status;
    private final String defaultDetail;

    ErrorCode(HttpStatus status, String defaultDetail) {
        this.status = status;
        this.defaultDetail = defaultDetail;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultDetail() {
        return defaultDetail;
    }
}
