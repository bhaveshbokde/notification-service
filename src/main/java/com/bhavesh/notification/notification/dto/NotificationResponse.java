package com.bhavesh.notification.notification.dto;

import com.bhavesh.notification.notification.domain.Notification;
import com.bhavesh.notification.notification.domain.NotificationChannel;
import com.bhavesh.notification.notification.domain.NotificationPriority;
import com.bhavesh.notification.notification.domain.NotificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A notification as the API presents it.
 *
 * <p>The entity is never serialised directly. Two reasons, and the second is
 * the one that matters: a separate type makes exposure opt-in, so a new entity
 * field is invisible until someone adds it here on purpose — and the recipient
 * is masked, which an entity mapping would quietly stop doing the moment
 * somebody added a getter.
 *
 * <h2>Why the recipient is masked and the body omitted</h2>
 * Both are personal data. The body in particular may be a one-time code or a
 * password-reset link; returning it from a GET would mean anyone who could read
 * a notification could read the secret it carried. The caller supplied both
 * values and already knows them, so returning them buys nothing and costs a
 * disclosure risk.
 *
 * @param simulated true when no real email or SMS was sent. Surfaced in the API
 *                  rather than only in the logs, because a client that believed
 *                  a simulated send was real would be badly misled.
 */
@Schema(name = "NotificationResponse", description = "A notification and its current state")
public record NotificationResponse(

        @Schema(example = "0199c4a1-7b3e-7c2d-8f41-2a9e5d6c8b70")
        UUID id,

        @Schema(example = "EMAIL")
        NotificationChannel channel,

        @Schema(example = "SENT")
        NotificationStatus status,

        @Schema(example = "NORMAL")
        NotificationPriority priority,

        @Schema(example = "p***a@example.com",
                description = "Masked. The full value is never returned.")
        String recipient,

        @Schema(example = "Your verification code")
        String subject,

        @Schema(description = "Caller metadata, echoed back verbatim.")
        String metadata,

        @Schema(example = "1")
        int attemptCount,

        @Schema(example = "3")
        int maxAttempts,

        @Schema(example = "Connection timed out",
                description = "Sanitised. Never a stack trace or a credential.")
        String lastError,

        @Schema(description = "When the next retry is due, if one is scheduled.")
        Instant nextAttemptAt,

        Instant createdAt,
        Instant updatedAt,

        @Schema(description = "When a consumer first picked it up. Null means it was never claimed.")
        Instant processingStartedAt,

        @Schema(description = "When it reached a terminal state.")
        Instant completedAt,

        @Schema(example = "true",
                description = "TRUE means NO REAL EMAIL OR SMS WAS SENT — the provider is simulated.")
        boolean simulated
) {

    public static NotificationResponse from(Notification n, boolean simulated) {
        return new NotificationResponse(
                n.getId(),
                n.getChannel(),
                n.getStatus(),
                n.getPriority(),
                n.getMaskedRecipient(),
                n.getSubject(),
                n.getMetadata(),
                n.getAttemptCount(),
                n.getMaxAttempts(),
                n.getLastError(),
                n.getNextAttemptAt(),
                n.getCreatedAt(),
                n.getUpdatedAt(),
                n.getProcessingStartedAt(),
                n.getCompletedAt(),
                simulated);
    }
}
