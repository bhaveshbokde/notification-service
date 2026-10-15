package com.bhavesh.notification.security;

import com.bhavesh.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authentication, authorization and data isolation, end to end.
 *
 * <p>These assert on behaviour an attacker would actually probe rather than on
 * configuration being present.
 */
class SecurityIT extends AbstractIntegrationTest {

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        userToken = userToken("ordinary-caller");
        adminToken = adminToken("operator");
    }

    @Nested
    @DisplayName("401 — not authenticated")
    class Unauthenticated {

        @ParameterizedTest
        @CsvSource({
                "GET,  /api/v1/notifications",
                "POST, /api/v1/notifications",
                "GET,  /api/v1/auth/me",
                "GET,  /api/v1/admin/dead-letters",
                "GET,  /actuator/metrics",
        })
        @DisplayName("a protected endpoint without a token is 401 in the standard shape")
        void requiresAToken(String method, String path) throws Exception {
            mockMvc.perform(request(HttpMethod.valueOf(method), path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }

        @Test
        @DisplayName("login and the health probes are public")
        void publicEndpoints() throws Exception {
            mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"nobody","password":"wrong"}"""))
                    .andExpect(status().isUnauthorized());   // reached the handler, not the filter
        }

        @Test
        @DisplayName("a 401 carries a WWW-Authenticate header, per RFC 6750")
        void wwwAuthenticateHeader() throws Exception {
            mockMvc.perform(get("/api/v1/notifications"))
                    .andExpect(header().string("WWW-Authenticate",
                            org.hamcrest.Matchers.containsString("Bearer")));
        }

        @Test
        @DisplayName("a tampered token is TOKEN_INVALID, distinct from a missing one")
        void tamperedToken() throws Exception {
            String tampered = userToken.substring(0, userToken.length() - 1) + "X";

            mockMvc.perform(get("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, tampered))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        }

        /**
         * Verification is built with {@code verifyWith(SecretKey)}, so JJWT
         * accepts only a MAC algorithm and the header's {@code alg} never
         * selects the strategy.
         */
        @Test
        @DisplayName("an alg:none forgery claiming ROLE_ADMIN is refused")
        void algNoneForgery() throws Exception {
            String header = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("{\"sub\":\"1\",\"rol\":\"ROLE_ADMIN\",\"usr\":\"forged\","
                     + "\"iss\":\"notification-service\",\"exp\":9999999999}").getBytes());

            mockMvc.perform(get("/api/v1/admin/dead-letters")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + header + "." + payload + "."))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "Basic dXNlcjpwYXNz", "Bearer", "Bearer    ", "Token abc123"})
        @DisplayName("a malformed Authorization header is treated as no credential")
        void malformedHeader(String value) throws Exception {
            mockMvc.perform(get("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, value))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        void correctCredentialsIssueAToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"ordinary-caller","password":"%s"}"""
                                    .formatted(TEST_PASSWORD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.role").value("ROLE_USER"))
                    .andExpect(jsonPath("$.expiresInSeconds").value(3600));
        }

        /**
         * Identical bodies for both cases, so the endpoint cannot be used to
         * enumerate which service accounts exist.
         */
        @Test
        @DisplayName("a wrong password and an unknown username are indistinguishable")
        void failuresAreIndistinguishable() throws Exception {
            String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"ordinary-caller","password":"wrong"}"""))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            String unknownUser = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"does-not-exist","password":"wrong"}"""))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            assertThat(json(wrongPassword).get("code").asText())
                    .isEqualTo(json(unknownUser).get("code").asText())
                    .isEqualTo("INVALID_CREDENTIALS");
            assertThat(json(wrongPassword).get("detail").asText())
                    .isEqualTo(json(unknownUser).get("detail").asText());
        }

        @Test
        @DisplayName("the token never contains the password hash")
        void tokenCarriesNoHash() throws Exception {
            String body = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"ordinary-caller","password":"%s"}"""
                                    .formatted(TEST_PASSWORD)))
                    .andReturn().getResponse().getContentAsString();

            String token = json(body).get("accessToken").asText();
            // A JWT is signed, not encrypted — anyone holding it can read it.
            String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));

            assertThat(payload).doesNotContain("$2a$").doesNotContain(TEST_PASSWORD);
        }
    }

    @Nested
    @DisplayName("403 — authenticated but not permitted")
    class Forbidden {

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/v1/admin/dead-letters",
                "/api/v1/admin/dead-letters/count",
                "/actuator/metrics",
        })
        @DisplayName("an ordinary caller reaching an admin endpoint is 403, not 401")
        void ordinaryCallerIsForbidden(String path) throws Exception {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, userToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("an administrator can reach the same endpoints")
        void administratorCan() throws Exception {
            mockMvc.perform(get("/api/v1/admin/dead-letters")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isOk());
        }

        /**
         * 403 means "I know who you are and the answer is no". Returning 401
         * would send a correctly authenticated client into a pointless
         * re-authentication loop.
         */
        @Test
        @DisplayName("403 is distinguishable from 401 by both status and code")
        void notTheSameAs401() throws Exception {
            String anonymous = mockMvc.perform(get("/api/v1/admin/dead-letters"))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            String ordinary = mockMvc.perform(get("/api/v1/admin/dead-letters")
                            .header(HttpHeaders.AUTHORIZATION, userToken))
                    .andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();

            assertThat(json(anonymous).get("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
            assertThat(json(ordinary).get("code").asText()).isEqualTo("ACCESS_DENIED");
        }
    }

    @Nested
    @DisplayName("data isolation")
    class Isolation {

        private UUID otherCallersNotification;
        private String intruderToken;

        @BeforeEach
        void twoCallersOneNotification() throws Exception {
            String body = mockMvc.perform(post("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"channel":"IN_APP","recipient":"user-1","body":"secret code 418290"}"""))
                    .andReturn().getResponse().getContentAsString();
            otherCallersNotification = UUID.fromString(json(body).get("id").asText());

            intruderToken = userToken("intruder");
        }

        /**
         * 404 rather than 403, deliberately. A 403 would confirm the id is
         * real, turning every endpoint into an existence oracle. Because the
         * repository query includes {@code created_by}, "missing" and "not
         * yours" are genuinely indistinguishable in the code too.
         */
        @Test
        @DisplayName("another caller's notification is 404, not 403")
        void cannotReadAnotherCallersNotification() throws Exception {
            mockMvc.perform(get("/api/v1/notifications/" + otherCallersNotification)
                            .header(HttpHeaders.AUTHORIZATION, intruderToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        @DisplayName("a real-but-not-yours id is indistinguishable from one that does not exist")
        void existenceIsNotLeaked() throws Exception {
            String real = mockMvc.perform(get("/api/v1/notifications/" + otherCallersNotification)
                            .header(HttpHeaders.AUTHORIZATION, intruderToken))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            String imaginary = mockMvc.perform(get("/api/v1/notifications/" + UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, intruderToken))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            var realBody = json(real);
            var imaginaryBody = json(imaginary);

            // The code, the status and the set of fields must be identical. The
            // detail echoes the id the caller themselves supplied, so it
            // legitimately differs — that is their own input coming back, not
            // information about what exists.
            assertThat(realBody.get("code").asText())
                    .isEqualTo(imaginaryBody.get("code").asText())
                    .isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(realBody.get("status").asInt())
                    .isEqualTo(imaginaryBody.get("status").asInt());

            var realFields = new java.util.ArrayList<String>();
            realBody.fieldNames().forEachRemaining(realFields::add);
            var imaginaryFields = new java.util.ArrayList<String>();
            imaginaryBody.fieldNames().forEachRemaining(imaginaryFields::add);

            assertThat(realFields)
                    .as("a differing set of fields would itself be a signal")
                    .containsExactlyInAnyOrderElementsOf(imaginaryFields);

            // And the detail says nothing about the notification beyond the id
            // that was asked for.
            assertThat(realBody.get("detail").asText())
                    .isEqualTo("Notification %s was not found".formatted(otherCallersNotification));
        }

        @Test
        @DisplayName("the status endpoint is scoped too — not just the detail one")
        void statusEndpointIsAlsoScoped() throws Exception {
            mockMvc.perform(get("/api/v1/notifications/" + otherCallersNotification + "/status")
                            .header(HttpHeaders.AUTHORIZATION, intruderToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("the listing shows only the caller's own notifications")
        void listingIsScoped() throws Exception {
            mockMvc.perform(get("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, intruderToken))
                    .andExpect(jsonPath("$.totalElements").value(0));

            mockMvc.perform(get("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, userToken))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        /**
         * An administrator genuinely needs to see everything — that is the
         * operational job. The breadth is why admin reads live on their own
         * prefix with their own authorization rule.
         */
        @Test
        @DisplayName("an administrator sees every caller's notifications")
        void administratorSeesEverything() throws Exception {
            mockMvc.perform(get("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(jsonPath("$.totalElements").value(1));

            mockMvc.perform(get("/api/v1/notifications/" + otherCallersNotification)
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("injection resistance")
    class Injection {

        @ParameterizedTest
        @ValueSource(strings = {
                "' OR 1=1 --",
                "'; DROP TABLE notifications; --",
                "' UNION SELECT password_hash FROM users --",
        })
        @DisplayName("SQL metacharacters in a username are matched as literal text")
        void loginIsParameterised(String payload) throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    java.util.Map.of("username", payload, "password", "x"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

            // The tables are intact and no hash leaked into any response.
            assertThat(countRows("users")).isEqualTo(2);
        }

        @Test
        @DisplayName("a non-UUID path variable is 400, not 500")
        void malformedPathVariable() throws Exception {
            mockMvc.perform(get("/api/v1/notifications/not-a-uuid")
                            .header(HttpHeaders.AUTHORIZATION, userToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        }
    }

    @Nested
    @DisplayName("error bodies")
    class ErrorBodies {

        @Test
        @DisplayName("no stack trace, SQL or internal path reaches the client")
        void noInternalsLeak() throws Exception {
            String body = mockMvc.perform(get("/api/v1/notifications/" + UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, userToken))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .doesNotContain("org.hibernate")
                    .doesNotContain("org.springframework")
                    .doesNotContain("jdbc:postgresql")
                    .doesNotContain("at com.bhavesh")
                    .doesNotContain("SELECT");
        }

        /**
         * Needs a valid token. Security evaluates before the dispatcher, so an
         * <em>unauthenticated</em> request with the wrong method is 401 rather
         * than 405 — which is the better behaviour: telling an anonymous caller
         * "that method is not allowed here" confirms the path exists.
         */
        @Test
        @DisplayName("an unsupported method on a permitted path is 405, in the standard shape")
        void methodNotAllowed() throws Exception {
            mockMvc.perform(request(HttpMethod.DELETE, "/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, userToken))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        }

        /**
         * The flip side of the above, asserted explicitly because it is a
         * deliberate property rather than an accident of ordering.
         */
        @Test
        @DisplayName("an unauthenticated request with a wrong method is 401, not 405")
        void unauthenticatedWrongMethodDoesNotRevealThePath() throws Exception {
            mockMvc.perform(request(HttpMethod.DELETE, "/api/v1/auth/login"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }

        @Test
        @DisplayName("an unsupported content type is 415")
        void unsupportedMediaType() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("username=x"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        }
    }
}
