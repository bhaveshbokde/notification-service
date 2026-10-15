package com.bhavesh.notification.notification.dto;

import com.bhavesh.notification.notification.domain.NotificationChannel;
import com.bhavesh.notification.notification.domain.NotificationPriority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * A request to deliver one notification.
 *
 * <p>A record, so every field is final and the object cannot be mutated between
 * validation and use.
 *
 * <h2>What is deliberately absent</h2>
 * There is no {@code status}, no {@code attemptCount}, no {@code id} and no
 * {@code createdBy}. Those are decided by the server. Accepting them would let
 * a caller submit a notification that claims to be already SENT, or attribute
 * one to another account — and a validation annotation rejecting them would be
 * a weaker version of simply not having the field.
 *
 * <h2>Cross-field rules live elsewhere</h2>
 * Bean Validation checks each field in isolation, so it cannot express "the
 * recipient must be an email address <em>when the channel is EMAIL</em>", or
 * "a subject is required for EMAIL but meaningless for SMS". Those are
 * enforced in {@code NotificationService} against
 * {@link NotificationChannel}, which owns the per-channel rules. Splitting them
 * across a custom {@code @ConstraintValidator} would put the same knowledge in
 * two places.
 */
@Schema(name = "CreateNotificationRequest", description = "A notification to deliver")
public record CreateNotificationRequest(

        @Schema(example = "EMAIL", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "EMAIL, SMS or IN_APP. Determines how `recipient` is validated.")
        @NotNull(message = "channel is required")
        NotificationChannel channel,

        @Schema(example = "priya@example.com", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Format depends on the channel: an email address, an E.164 "
                        + "phone number, or an internal user id.")
        @NotBlank(message = "recipient is required")
        @Size(max = 320, message = "must not exceed 320 characters")
        String recipient,

        @Schema(example = "Your verification code",
                description = "Required for EMAIL; ignored for SMS and IN_APP.")
        @Size(max = 255, message = "must not exceed 255 characters")
        String subject,

        @Schema(example = "Your code is 418290. It expires in 10 minutes.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "body is required")
        @Size(max = 10_000, message = "must not exceed 10000 characters")
        String body,

        @Schema(example = "HIGH", defaultValue = "NORMAL",
                description = "Affects ordering under backlog, never whether delivery happens.")
        NotificationPriority priority,

        /**
         * Caller metadata: a template name, a locale, a correlation id.
         *
         * <p>Stored as {@code jsonb} and echoed back, but never interpreted by
         * this service. A bounded map rather than a free-form string so the
         * size is checkable and the content is valid JSON by construction.
         */
        @Schema(description = "Arbitrary caller metadata. Stored and returned, never interpreted.",
                example = "{\"template\":\"otp-v2\",\"locale\":\"en-IN\"}")
        @Size(max = 20, message = "must not contain more than 20 entries")
        Map<String, String> metadata,

        /**
         * Overrides the configured attempt budget for this one notification.
         *
         * <p>Capped at 10 by the schema. A caller asking for a thousand retries
         * against a provider that is permanently rejecting the message would
         * spend money and fill the pipeline to no purpose.
         */
        @Schema(example = "3", defaultValue = "3",
                description = "Total delivery attempts including the first. 1-10.")
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 10, message = "must not exceed 10")
        Integer maxAttempts
) {

    /**
     * Never prints {@code body}, {@code subject} or {@code recipient}.
     *
     * <p>Notification content is the most sensitive thing this service handles
     * — one-time codes, password-reset links, order details — and
     * {@code toString} ends up in exception messages and log lines by accident
     * constantly.
     */
    @Override
    public String toString() {
        return "CreateNotificationRequest{channel=%s, priority=%s, bodyLength=%d}"
                .formatted(channel, priority, body == null ? 0 : body.length());
    }
}
