package com.bhavesh.notification.notification.dto;

import com.bhavesh.notification.notification.domain.Notification;
import com.bhavesh.notification.notification.domain.NotificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The delivery status of one notification, with its full attempt history.
 *
 * <p>A separate, narrower endpoint from the full notification because the two
 * answer different questions and are polled at very different rates. "Has it
 * been delivered yet?" is asked repeatedly by a client waiting on an outcome;
 * "what was in it?" is asked once. Keeping them apart means the hot path does
 * not carry the metadata and subject with every poll.
 *
 * @param terminal       whether the status can still change. A polling client
 *                       uses this to know when to stop, rather than hard-coding
 *                       a list of terminal statuses that will drift.
 * @param attempts       every provider call, oldest first. The answer to "why
 *                       did it fail?" — which a single `lastError` cannot give
 *                       once a later attempt has overwritten it.
 */
@Schema(name = "NotificationStatusResponse", description = "Delivery status and attempt history")
public record NotificationStatusResponse(

        UUID id,

        @Schema(example = "RETRY_SCHEDULED")
        NotificationStatus status,

        @Schema(example = "false", description = "True when the status can no longer change.")
        boolean terminal,

        @Schema(example = "2")
        int attemptCount,

        @Schema(example = "3")
        int maxAttempts,

        @Schema(example = "Provider temporarily unavailable")
        String lastError,

        @Schema(description = "When the next retry is due, if one is scheduled.")
        Instant nextAttemptAt,

        Instant createdAt,
        Instant processingStartedAt,
        Instant completedAt,

        List<AttemptResponse> attempts,

        @Schema(example = "true",
                description = "TRUE means NO REAL EMAIL OR SMS WAS SENT.")
        boolean simulated
) {

    public static NotificationStatusResponse from(Notification n,
                                                  List<AttemptResponse> attempts,
                                                  boolean simulated) {
        return new NotificationStatusResponse(
                n.getId(),
                n.getStatus(),
                n.getStatus().isTerminal(),
                n.getAttemptCount(),
                n.getMaxAttempts(),
                n.getLastError(),
                n.getNextAttemptAt(),
                n.getCreatedAt(),
                n.getProcessingStartedAt(),
                n.getCompletedAt(),
                attempts,
                simulated);
    }
}
