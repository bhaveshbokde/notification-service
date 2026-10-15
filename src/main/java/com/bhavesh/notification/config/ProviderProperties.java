package com.bhavesh.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Binds {@code notification.providers.*}.
 *
 * @param simulationEnabled when true, NO EMAIL OR SMS IS ACTUALLY SENT. The
 *                          adapters simulate a provider locally so the service
 *                          runs on a developer machine with no paid accounts,
 *                          no SMTP credentials and no real phone numbers.
 *                          <p>
 *                          Default true, and the API response, the logs and the
 *                          documentation all say so explicitly. Reporting a
 *                          simulated send as a real one is a lie that is very
 *                          easy to tell by accident, so it is stated in three
 *                          places rather than none.
 *                          <p>
 *                          {@code ProviderConfiguration} refuses to start under
 *                          the {@code prod} profile when this is true.
 *
 * @param simulatedLatency  artificial delay before the adapter answers, so the
 *                          asynchronous pipeline is observable rather than
 *                          completing before anyone can watch it.
 *
 * @param failureRate       probability of a spontaneous transient failure,
 *                          0.0–1.0. Zero by default: a random failure rate
 *                          makes a test suite flaky and a demonstration
 *                          unreproducible. Tests request a specific outcome per
 *                          message instead. Raise it only to watch retry
 *                          behaviour under sustained load.
 */
@ConfigurationProperties(prefix = "notification.providers")
public record ProviderProperties(
        @DefaultValue("true") boolean simulationEnabled,
        @DefaultValue("PT0.05S") Duration simulatedLatency,
        @DefaultValue("0.0") double failureRate
) {
}
