package com.bhavesh.notification.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds {@code notification.jwt.*}.
 *
 * <p>{@code @Validated} with {@code @NotBlank} means a missing signing key
 * fails startup rather than the first login attempt in production.
 */
@ConfigurationProperties(prefix = "notification.jwt")
@Validated
public record JwtProperties(

        @NotBlank(message = "notification.jwt.secret must be set (see .env.example)")
        String secret,

        @DefaultValue("notification-service")
        String issuer,

        /**
         * 60 minutes. Longer than a browser-facing API would use, because the
         * callers here are services holding a token for a batch of work, not
         * humans clicking around. There is no refresh token: a service can
         * re-authenticate with its credentials whenever it needs to, so the
         * extra moving part would buy nothing.
         */
        @DefaultValue("PT60M")
        Duration accessTokenTtl,

        @DefaultValue("PT30S")
        Duration clockSkew
) {
}
