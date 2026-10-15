package com.bhavesh.notification.provider.simulated;

import com.bhavesh.notification.config.ProviderProperties;
import com.bhavesh.notification.notification.domain.NotificationChannel;
import org.springframework.stereotype.Component;

/**
 * Simulated EMAIL adapter. <strong>Sends nothing.</strong>
 *
 * <p>See {@link AbstractSimulatedProvider} for how outcomes are scripted and
 * how a real provider would replace this without touching the pipeline.
 */
@Component
public class SimulatedEmailProvider extends AbstractSimulatedProvider {

    public SimulatedEmailProvider(ProviderProperties properties) {
        super(properties);
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }
}
