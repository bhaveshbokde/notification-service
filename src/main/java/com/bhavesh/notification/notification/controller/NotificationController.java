package com.bhavesh.notification.notification.controller;

import com.bhavesh.notification.common.dto.PageResponse;
import com.bhavesh.notification.common.exception.ApiException;
import com.bhavesh.notification.common.exception.ErrorCode;
import com.bhavesh.notification.idempotency.service.IdempotencyService;
import com.bhavesh.notification.notification.domain.NotificationChannel;
import com.bhavesh.notification.notification.domain.NotificationStatus;
import com.bhavesh.notification.notification.dto.CreateNotificationRequest;
import com.bhavesh.notification.notification.dto.NotificationResponse;
import com.bhavesh.notification.notification.dto.NotificationStatusResponse;
import com.bhavesh.notification.notification.service.NotificationService;
import com.bhavesh.notification.ratelimit.RateLimiter;
import com.bhavesh.notification.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The notification API.
 *
 * <p>Thin on purpose. A controller's job is HTTP: bind, validate, delegate,
 * choose a status code and set headers. Every decision that matters is in
 * {@link NotificationService}, which is what makes those decisions
 * unit-testable without a servlet container.
 *
 * <p>Every read is scoped by {@code principal.getId()} unless the caller is an
 * administrator, so one service account cannot read another's notifications —
 * which matters because a notification body routinely contains a one-time code
 * belonging to someone else's user.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Validated
@Tag(name = "Notifications", description = "Submit notifications and track their delivery")
public class NotificationController {

    private static final String ENDPOINT = "POST /api/v1/notifications";
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService notificationService;
    private final IdempotencyService idempotencyService;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public NotificationController(NotificationService notificationService,
                                  IdempotencyService idempotencyService,
                                  RateLimiter rateLimiter,
                                  ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.idempotencyService = idempotencyService;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    /**
     * Accepts a notification for delivery.
     *
     * <p>Returns <strong>202 Accepted</strong>, not 201 Created. Nothing has
     * been delivered when this returns — the notification is durably recorded
     * and an outbox event is committed, and that is all. Returning 201 would
     * imply a completed action the client might act on.
     */
    @PostMapping
    @Operation(summary = "Submit a notification",
            description = """
                    Validates the request, records the notification and its outbox event in a \
                    single database transaction, and returns immediately. Delivery happens \
                    asynchronously; poll `/{id}/status` for the outcome.

                    Send an `Idempotency-Key` header to make a retry safe: the same key with \
                    the same body replays the original response, and the same key with a \
                    different body is a 409.

                    **No real email or SMS is sent** when the service runs with simulated \
                    providers — see the `simulated` field in the response.""")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted for delivery"),
            @ApiResponse(responseCode = "200", description = "Replayed from a previous Idempotency-Key"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token", content = @Content),
            @ApiResponse(responseCode = "409", description = "Idempotency key reused with a different body", content = @Content),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<NotificationResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Optional. Retrying with the same key replays the original response.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateNotificationRequest request) {

        RateLimiter.Decision decision = rateLimiter.tryConsume(principal.getId());
        if (!decision.allowed()) {
            throw rateLimitException(decision);
        }

        var result = idempotencyService.execute(
                principal.getId(), ENDPOINT, idempotencyKey,
                canonicalise(request),
                NotificationResponse.class,
                NotificationResponse::id,
                () -> notificationService.create(request, principal.getId()));

        // 200 for a replay: nothing was accepted this time, so 202 would be a
        // false statement about what the request did.
        return ResponseEntity
                .status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .header("X-RateLimit-Limit", String.valueOf(decision.limit()))
                .header("X-RateLimit-Remaining", String.valueOf(decision.remaining()))
                .body(result.value());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a notification",
            description = "404 for a notification that does not exist and for one belonging to "
                    + "another caller — the API does not confirm that someone else's id is real. "
                    + "The recipient is masked and the body is never returned.")
    public ResponseEntity<NotificationResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {

        return ResponseEntity.ok(
                notificationService.get(id, principal.getId(), principal.isAdmin()));
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "Fetch delivery status and attempt history",
            description = """
                    The endpoint to poll while waiting for an outcome. `terminal` tells you when \
                    to stop polling, rather than requiring the client to hard-code a list of \
                    terminal statuses.

                    `attempts` carries every provider call with its outcome, duration and \
                    sanitised error — which is what answers "why did this fail?" after a later \
                    attempt has overwritten `lastError`.""")
    public ResponseEntity<NotificationStatusResponse> getStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {

        return ResponseEntity.ok(
                notificationService.getStatus(id, principal.getId(), principal.isAdmin()));
    }

    @GetMapping
    @Operation(summary = "List notifications",
            description = "Scoped to the caller unless they are an administrator. "
                    + "All filters are optional and combine.")
    public ResponseEntity<PageResponse<NotificationResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,

            @Parameter(description = "PENDING, PROCESSING, RETRY_SCHEDULED, SENT, FAILED or DEAD_LETTERED")
            @RequestParam(required = false) NotificationStatus status,

            @Parameter(description = "EMAIL, SMS or IN_APP")
            @RequestParam(required = false) NotificationChannel channel,

            @Parameter(description = "ISO-8601 instant, inclusive. e.g. 2026-10-15T00:00:00Z")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,

            @Parameter(description = "ISO-8601 instant, inclusive.")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore,

            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        // Sorting is fixed rather than caller-supplied. A free-form sort
        // parameter reaches the generated ORDER BY, so an unindexed or absurd
        // field becomes a cheap way to make the database work hard. The one
        // ordering this endpoint needs is newest-first, and it is indexed.
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));

        return ResponseEntity.ok(notificationService.list(
                principal.getId(), principal.isAdmin(),
                status, channel, createdAfter, createdBefore, pageable));
    }

    /**
     * The canonical form of a request, for the idempotency hash.
     *
     * <p>Serialising the DTO rather than hashing the raw body on purpose: the
     * raw bytes differ with key order and whitespace, so two semantically
     * identical retries would hash differently and the key would appear reused.
     * Jackson's output for a record is field-ordered and stable.
     */
    private String canonicalise(CreateNotificationRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST,
                    "Request could not be canonicalised for idempotency", e);
        }
    }

    /**
     * Builds the 429, or a 503 when the limiter itself is down and configured
     * to fail closed.
     *
     * <p>The distinction matters to a client: 429 means "you are going too
     * fast", 503 means "we cannot tell". Conflating them would have clients
     * backing off for the wrong reason.
     */
    private ApiException rateLimitException(RateLimiter.Decision decision) {
        if (!decision.limiterAvailable()) {
            return new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "The rate limiter is unavailable and the service is configured to fail closed");
        }
        Duration reset = decision.resetAfter();
        return new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED,
                "Rate limit of %d requests exceeded; retry in %d second(s)"
                        .formatted(decision.limit(), reset.toSeconds()));
    }
}
