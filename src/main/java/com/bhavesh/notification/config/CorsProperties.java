package com.bhavesh.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Binds {@code notification.cors.*}.
 *
 * <p>An explicit origin list, never a wildcard. This is an internal service
 * whose callers are other services; a browser origin list exists only for a
 * local operator dashboard.
 */
@ConfigurationProperties(prefix = "notification.cors")
public record CorsProperties(
        @DefaultValue({"http://localhost:3000", "http://localhost:5173"})
        List<String> allowedOrigins
) {
}
