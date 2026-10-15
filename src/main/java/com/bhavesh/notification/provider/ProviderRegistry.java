package com.bhavesh.notification.provider;

import com.bhavesh.notification.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the adapter for a channel.
 *
 * <p>Spring injects every {@link NotificationProvider} bean on the classpath
 * and this indexes them by channel. Adding a channel means adding an enum
 * constant and a bean; no registration list to update and no switch statement
 * to forget.
 *
 * <h2>Why it fails fast at startup</h2>
 * A channel with no adapter would otherwise surface as a runtime failure on the
 * first notification of that kind — which, for a channel used rarely, could be
 * days after the deployment that broke it. The constructor asserts that every
 * {@link NotificationChannel} has exactly one provider, so a missing or
 * duplicated adapter stops the application immediately.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<NotificationChannel, NotificationProvider> byChannel =
            new EnumMap<>(NotificationChannel.class);

    public ProviderRegistry(List<NotificationProvider> providers) {
        for (NotificationProvider provider : providers) {
            NotificationProvider existing = byChannel.put(provider.channel(), provider);
            if (existing != null) {
                // Two beans claiming one channel means delivery would depend on
                // bean ordering, which is not something to discover in
                // production.
                throw new IllegalStateException(
                        "Two providers registered for channel %s: %s and %s. Exactly one is required."
                                .formatted(provider.channel(),
                                        existing.getClass().getSimpleName(),
                                        provider.getClass().getSimpleName()));
            }
        }

        for (NotificationChannel channel : NotificationChannel.values()) {
            if (!byChannel.containsKey(channel)) {
                throw new IllegalStateException(
                        "No NotificationProvider registered for channel " + channel
                                + ". Every channel needs an adapter, or notifications on that "
                                + "channel would fail at delivery time rather than at startup.");
            }
        }

        boolean anySimulated = byChannel.values().stream().anyMatch(NotificationProvider::isSimulated);
        if (anySimulated) {
            // Deliberately WARN, not INFO. Someone reading a startup log should
            // not be able to miss that nothing is actually being delivered.
            log.warn("SIMULATION MODE: no real email or SMS will be sent. Providers: {}",
                    byChannel.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue().getClass().getSimpleName())
                            .toList());
        } else {
            log.info("Live providers registered: {}", byChannel.keySet());
        }
    }

    public NotificationProvider forChannel(NotificationChannel channel) {
        NotificationProvider provider = byChannel.get(channel);
        if (provider == null) {
            // Unreachable given the startup check, but an explicit failure
            // beats a NullPointerException three frames deeper.
            throw new IllegalStateException("No provider for channel " + channel);
        }
        return provider;
    }

    /** True when any adapter is simulating — surfaced in API responses. */
    public boolean isSimulated() {
        return byChannel.values().stream().anyMatch(NotificationProvider::isSimulated);
    }
}
