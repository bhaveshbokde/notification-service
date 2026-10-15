package com.bhavesh.notification.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Guards the one configuration mistake that would silently do real damage.
 *
 * <p>If simulation were left enabled in production, the service would report
 * every notification as SENT while delivering nothing. Nobody would receive a
 * password reset, nobody would receive a one-time code, and every dashboard
 * would be green — the failure mode is invisible precisely because the
 * simulator is good at pretending.
 *
 * <p>So the application refuses to start rather than running in that state.
 * Failing loudly at deployment is recoverable in minutes; discovering it from
 * a user complaint days later is not.
 *
 * <p>The check is here rather than in the {@code prod} profile alone because a
 * profile value can be overridden by an environment variable, and an
 * environment variable is exactly how this mistake would be made.
 */
@Configuration
public class ProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfiguration.class);

    private final ProviderProperties properties;
    private final Environment environment;

    public ProviderConfiguration(ProviderProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void assertSimulationIsNotEnabledInProduction() {
        boolean prod = environment.matchesProfiles("prod");

        if (prod && properties.simulationEnabled()) {
            throw new IllegalStateException("""
                    notification.providers.simulation-enabled is TRUE under the 'prod' profile.

                    The service would report every notification as delivered while sending \
                    nothing at all — no email, no SMS. Refusing to start.

                    Set NOTIFICATION_SIMULATION=false and register real provider adapters \
                    before deploying to production.""");
        }

        if (prod) {
            log.info("Simulation disabled under the prod profile, as required");
        } else {
            // WARN, not INFO. Someone reading a startup log should not be able
            // to miss that nothing is actually being delivered.
            log.warn("""
                    ====================================================================
                      SIMULATION MODE — NO REAL EMAIL OR SMS WILL BE SENT
                      Notifications will be marked SENT without any message leaving this
                      machine. Every API response carries "simulated": true.
                    ====================================================================""");
        }
    }
}
