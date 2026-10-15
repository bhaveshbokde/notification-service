package com.bhavesh.notification.deadletter.dto;

import com.bhavesh.notification.deadletter.domain.DeadLetterEvent;
import com.bhavesh.notification.deadletter.domain.DeadLetterStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A dead-lettered message, as the administrative API presents it.
 *
 * <p>The payload is included, which is a deliberate exception to this
 * project's usual rule about not returning message content. The endpoint is
 * ROLE_ADMIN only, and an operator deciding whether to replay or discard a
 * poisoned message cannot do so without seeing what it actually contains —
 * withholding it would make the endpoint useless for its one purpose.
 */
@Schema(name = "DeadLetterResponse", description = "A message the pipeline could not process")
public record DeadLetterResponse(

        UUID id,

        @Schema(example = "notifications.requested.v1")
        String sourceTopic,

        @Schema(example = "1")
        Integer sourcePartition,

        @Schema(example = "4821")
        Long sourceOffset,

        String messageKey,

        @Schema(description = "The raw message. Truncated if it was very large.")
        String payload,

        @Schema(example = "Event payload could not be parsed: JsonParseException")
        String failureReason,

        String exceptionType,

        @Schema(description = "Null when the payload could not be associated with a notification.")
        UUID notificationId,

        @Schema(example = "UNRESOLVED")
        DeadLetterStatus status,

        @Schema(example = "true", description = "Whether this event can be replayed.")
        boolean replayable,

        Instant replayedAt,
        Long replayedBy,
        Instant createdAt
) {

    public static DeadLetterResponse from(DeadLetterEvent e) {
        return new DeadLetterResponse(
                e.getId(), e.getSourceTopic(), e.getSourcePartition(), e.getSourceOffset(),
                e.getMessageKey(), e.getPayload(), e.getFailureReason(), e.getExceptionType(),
                e.getNotificationId(), e.getStatus(), e.isReplayable(),
                e.getReplayedAt(), e.getReplayedBy(), e.getCreatedAt());
    }
}
