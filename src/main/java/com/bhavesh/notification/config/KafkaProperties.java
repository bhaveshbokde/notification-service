package com.bhavesh.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code notification.kafka.*}.
 *
 * <p>Topic names are namespaced and versioned. The version suffix means a
 * breaking payload change can run alongside the old topic during a migration
 * rather than requiring a flag day.
 *
 * @param partitions        three, so consumer concurrency can grow to three
 *                          without repartitioning. Partition count can be
 *                          increased later but never decreased, and increasing
 *                          it changes key-to-partition mapping — which breaks
 *                          per-key ordering for keys already in flight.
 * @param replicationFactor one, because this is a single-node development
 *                          broker. Anything above one fails topic creation
 *                          there. Production would use three.
 */
@ConfigurationProperties(prefix = "notification.kafka")
public record KafkaProperties(
        @DefaultValue("notifications.requested.v1") String topic,
        @DefaultValue("notifications.requested.v1.retry") String retryTopic,
        @DefaultValue("notifications.requested.v1.DLT") String dltTopic,
        @DefaultValue("3") int partitions,
        @DefaultValue("1") short replicationFactor
) {
}
