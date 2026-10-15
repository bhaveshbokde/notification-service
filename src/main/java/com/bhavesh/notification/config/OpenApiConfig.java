package com.bhavesh.notification.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI document and Swagger UI.
 *
 * <p>Declaring the bearer scheme here — rather than annotating every controller
 * — puts an "Authorize" button in the UI that applies the token to every
 * request, so the documentation is usable for exploration rather than only for
 * reading.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI notificationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Notification Service API")
                        .version("1.0.0")
                        .description("""
                                Asynchronous multi-channel notification delivery with a \
                                transactional outbox.

                                **No real email or SMS is sent.** The providers are simulated so \
                                the service runs locally with no paid accounts. Every response \
                                carries a `simulated` flag saying so.

                                **Flow** — `POST /notifications` records the notification and an \
                                outbox event in one database transaction and returns `202 \
                                Accepted`. A publisher drains the outbox to Kafka; a consumer \
                                delivers through a provider adapter and records the attempt. \
                                Poll `/notifications/{id}/status` for the outcome.

                                **Authentication** — `POST /auth/login` returns a JWT. Send it as \
                                `Authorization: Bearer <token>`.

                                **Idempotency** — send an `Idempotency-Key` header on \
                                `POST /notifications`. The same key with the same body replays \
                                the original response; the same key with a different body is a \
                                409.

                                **Errors** follow RFC 9457 Problem Details. Branch on the `code` \
                                field, which is stable; `detail` may be reworded.

                                **Rate limiting** — per caller, returning 429 with `Retry-After`. \
                                The limiter fails open if Redis is unavailable.""")
                        .contact(new Contact().name("Bhavesh Bokde"))
                        .license(new License().name("MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local development")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the accessToken returned by /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
