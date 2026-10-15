package com.bhavesh.notification.notification.dto;

import com.bhavesh.notification.notification.domain.AttemptOutcome;
import com.bhavesh.notification.notification.domain.NotificationAttempt;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One provider call, as the API presents it.
 *
 * <p>{@code providerReference} is included because it is what a support
 * conversation with a real provider would be conducted in terms of — "your
 * reference SIM-4F2A, our notification 0199c4a1". With the simulated adapters
 * it is synthetic, which the {@code simulated} flag on the parent response
 * makes clear.
 */
@Schema(name = "AttemptResponse", description = "A single delivery attempt")
public record AttemptResponse(

        @Schema(example = "1")
        int attemptNumber,

        @Schema(example = "TRANSIENT_FAILURE",
                description = "SUCCESS, TRANSIENT_FAILURE (will retry) or PERMANENT_FAILURE (will not).")
        AttemptOutcome outcome,

        @Schema(example = "SIM-EMAIL-4F2A91C3")
        String providerReference,

        @Schema(example = "Provider temporarily unavailable",
                description = "Sanitised. Never a stack trace.")
        String errorMessage,

        @Schema(example = "52", description = "Provider call duration in milliseconds.")
        Integer durationMs,

        Instant createdAt
) {

    public static AttemptResponse from(NotificationAttempt a) {
        return new AttemptResponse(
                a.getAttemptNumber(),
                a.getOutcome(),
                a.getProviderReference(),
                a.getErrorMessage(),
                a.getDurationMs(),
                a.getCreatedAt());
    }
}
