package com.bhavesh.notification.deadletter.controller;

import com.bhavesh.notification.common.dto.PageResponse;
import com.bhavesh.notification.deadletter.domain.DeadLetterStatus;
import com.bhavesh.notification.deadletter.dto.DeadLetterResponse;
import com.bhavesh.notification.deadletter.service.DeadLetterService;
import com.bhavesh.notification.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Dead-letter inspection and replay. Administrator only.
 *
 * <p>Lives under {@code /api/v1/admin} rather than alongside the notification
 * endpoints, because that prefix is restricted to {@code ROLE_ADMIN} by a
 * single rule in {@code SecurityConfig}. Putting an admin-only read next to the
 * caller-facing ones would make the authorization depend on matcher ordering,
 * which is a fragile thing to depend on.
 *
 * <p>The class-level {@code @PreAuthorize} is a second, independent check — if
 * someone later reorders the URL matchers and a broader rule shadows the admin
 * one, method security still refuses. Two mechanisms, so one mistake is not
 * enough.
 *
 * <p>These endpoints return the raw dead-lettered payload, which is the one
 * place this service returns message content. An operator deciding whether to
 * replay or discard a poisoned message cannot do so without seeing it, and
 * withholding it would make the endpoint useless for its only purpose.
 */
@RestController
@RequestMapping("/api/v1/admin/dead-letters")
@Validated
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Administration — dead letters",
        description = "Inspect and replay messages the pipeline could not process")
public class AdminDeadLetterController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DeadLetterService deadLetterService;

    public AdminDeadLetterController(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @GetMapping
    @Operation(summary = "List dead-lettered messages",
            description = """
                    These are messages the pipeline could not PROCESS — an unparseable payload, \
                    an event referencing a notification that does not exist, a consumer bug.

                    This is **not** where failed deliveries go. A notification a provider \
                    declined is a FAILED notification with a full attempt history, and the \
                    pipeline worked exactly as intended. A non-zero count here means something \
                    is wrong with this service.""")
    public ResponseEntity<PageResponse<DeadLetterResponse>> list(
            @RequestParam(required = false) DeadLetterStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        return ResponseEntity.ok(deadLetterService.list(status,
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE))));
    }

    @GetMapping("/count")
    @Operation(summary = "How many dead letters are unresolved",
            description = "The number to alert on. Also exposed as the Micrometer gauge "
                    + "`notification.deadletter.unresolved`.")
    public ResponseEntity<Map<String, Long>> unresolvedCount() {
        return ResponseEntity.ok(Map.of("unresolved", deadLetterService.unresolvedCount()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one dead-lettered message, including its raw payload")
    public ResponseEntity<DeadLetterResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(deadLetterService.get(id));
    }

    @PostMapping("/{id}/replay")
    @Operation(summary = "Replay a dead-lettered event",
            description = """
                    Re-publishes the event onto the main topic for processing, and marks the \
                    record REPLAYED so it cannot be replayed twice — which is what stops two \
                    operators working the same backlog from enqueuing it twice.

                    Only events that identified a notification can be replayed. An unparseable \
                    payload has nothing to replay to; fix the producer and discard it.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Replayed"),
            @ApiResponse(responseCode = "404", description = "No such dead letter", content = @Content),
            @ApiResponse(responseCode = "422", description = "Already resolved, or nothing to replay to", content = @Content)
    })
    public ResponseEntity<DeadLetterResponse> replay(@PathVariable UUID id,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(deadLetterService.replay(id, principal.getId()));
    }

    @PostMapping("/{id}/discard")
    @Operation(summary = "Close a dead letter as unrecoverable",
            description = "For messages that cannot be replayed — a malformed payload from a "
                    + "producer that has since been fixed, for instance. The record is kept; "
                    + "only its status changes.")
    public ResponseEntity<DeadLetterResponse> discard(@PathVariable UUID id,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(deadLetterService.discard(id, principal.getId()));
    }
}
