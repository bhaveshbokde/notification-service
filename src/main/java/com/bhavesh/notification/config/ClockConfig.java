package com.bhavesh.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Supplies the application {@link Clock}.
 *
 * <p>Every component that needs the current time injects this rather than
 * calling {@code Instant.now()}. That single indirection is what makes the
 * retry policy, the outbox lease expiry and the time-ordered id generation
 * testable: a test substitutes a fixed or offset clock and asserts exact
 * behaviour instead of sleeping and hoping.
 *
 * <p>UTC, not the system default. Storing and reasoning in UTC everywhere means
 * a server in a different timezone — or a daylight-saving transition — cannot
 * silently change when a retry becomes due.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
