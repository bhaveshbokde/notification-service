package com.bhavesh.notification;

import com.bhavesh.notification.config.AdminProperties;
import com.bhavesh.notification.config.CorsProperties;
import com.bhavesh.notification.config.DeliveryProperties;
import com.bhavesh.notification.config.JwtProperties;
import com.bhavesh.notification.config.KafkaProperties;
import com.bhavesh.notification.config.OutboxProperties;
import com.bhavesh.notification.config.ProviderProperties;
import com.bhavesh.notification.config.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Asynchronous multi-channel notification platform.
 *
 * <p>Accepts delivery requests over HTTP, persists them with a transactional
 * outbox, publishes them to Kafka, and processes them through simulated EMAIL,
 * SMS and IN_APP provider adapters with bounded retries and dead-lettering.
 *
 * <p><strong>No real email or SMS is sent.</strong> See
 * {@link ProviderProperties}.
 *
 * <h2>Why {@code @EnableScheduling}</h2>
 * The outbox publisher runs on a fixed-delay schedule. Fixed <em>delay</em>
 * rather than fixed rate: fixed rate would queue up overlapping executions if a
 * poll ever took longer than the interval, and the publisher must not run
 * concurrently with itself on one instance.
 */
@SpringBootApplication
@EnableConfigurationProperties({
        JwtProperties.class,
        KafkaProperties.class,
        OutboxProperties.class,
        DeliveryProperties.class,
        ProviderProperties.class,
        RateLimitProperties.class,
        AdminProperties.class,
        CorsProperties.class
})
@EnableScheduling
@EnableTransactionManagement
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
