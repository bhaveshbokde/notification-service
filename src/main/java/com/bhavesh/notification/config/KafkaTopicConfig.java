package com.bhavesh.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this service owns.
 *
 * <p>Spring creates them at startup through the admin client if they do not
 * exist. Relying on broker-side auto-creation instead would give defaults —
 * usually one partition — that cannot be changed later without a migration,
 * since reducing partition count is impossible and increasing it changes
 * key-to-partition mapping.
 *
 * <p>Declaring them is also self-documenting: the topics this service depends
 * on are visible in code rather than in someone's shell history.
 *
 * <p><strong>Note:</strong> on this development machine the Kafka broker is
 * shared with another project. The {@code notifications.} prefix keeps the two
 * sets disjoint, and nothing here touches or deletes a topic it did not
 * declare.
 */
@Configuration
public class KafkaTopicConfig {

    private final KafkaProperties properties;

    public KafkaTopicConfig(KafkaProperties properties) {
        this.properties = properties;
    }

    /**
     * The main topic.
     *
     * <p>Three partitions so consumer concurrency can grow to three without
     * repartitioning. Messages are keyed by notification id, so all events for
     * one notification land on the same partition and keep their relative
     * order — a retry event can never be consumed before the original request.
     */
    @Bean
    public NewTopic notificationsTopic() {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(properties.replicationFactor())
                .build();
    }

    /**
     * The retry topic.
     *
     * <p>Separate from the main topic so a backlog of retries cannot delay
     * first attempts. If they shared a topic, a provider outage would fill the
     * partitions with retries and new notifications would queue behind them.
     */
    @Bean
    public NewTopic notificationsRetryTopic() {
        return TopicBuilder.name(properties.retryTopic())
                .partitions(properties.partitions())
                .replicas(properties.replicationFactor())
                .build();
    }

    /**
     * The dead-letter topic.
     *
     * <p>One partition: dead letters should be rare, ordering across them does
     * not matter, and a single partition makes manual inspection with a console
     * consumer straightforward.
     */
    @Bean
    public NewTopic notificationsDltTopic() {
        return TopicBuilder.name(properties.dltTopic())
                .partitions(1)
                .replicas(properties.replicationFactor())
                .build();
    }
}
