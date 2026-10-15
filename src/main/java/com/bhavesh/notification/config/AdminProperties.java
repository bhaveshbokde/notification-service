package com.bhavesh.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code notification.admin.*} — the development bootstrap account.
 *
 * <p>Both values default to empty, and {@link #isConfigured()} is false unless
 * both are supplied. Absent configuration means no account is created, not a
 * default one: a fallback password in source is a backdoor regardless of how
 * the comment describes it.
 */
@ConfigurationProperties(prefix = "notification.admin")
public record AdminProperties(
        @DefaultValue("") String username,
        @DefaultValue("") String password
) {

    public boolean isConfigured() {
        return username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }
}
