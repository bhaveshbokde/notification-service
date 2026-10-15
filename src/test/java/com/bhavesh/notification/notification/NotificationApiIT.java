package com.bhavesh.notification.notification;

import com.bhavesh.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The notification API end to end, including the asynchronous pipeline. */
class NotificationApiIT extends AbstractIntegrationTest {

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        token = userToken("api-caller");
    }

    private String submit(String body, String idempotencyKey) throws Exception {
        var request = post("/api/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private static String emailBody(String recipient) {
        return """
                {"channel":"EMAIL","recipient":"%s","subject":"Your code",
                 "body":"Your code is 418290.","priority":"HIGH"}
                """.formatted(recipient);
    }

    @Nested
    @DisplayName("submission")
    class Submission {

        /**
         * 202, not 201. Nothing has been delivered when this returns — the
         * notification is recorded and an outbox event is committed, and that
         * is all. 201 would imply a completed action.
         */
        @Test
        @DisplayName("returns 202 Accepted with the notification PENDING")
        void acceptsAndReturnsPending() throws Exception {
            mockMvc.perform(post("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(emailBody("priya@example.com")))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.attemptCount").value(0))
                    .andExpect(jsonPath("$.completedAt").doesNotExist())
                    .andExpect(jsonPath("$.simulated").value(true));
        }

        @Test
        @DisplayName("the response masks the recipient and never contains the body")
        void doesNotLeakContent() throws Exception {
            String body = submit(emailBody("priya@example.com"), null);

            assertThat(body)
                    .doesNotContain("priya@example.com")
                    .doesNotContain("418290")
                    .contains("p***a@example.com");
        }

        @Test
        @DisplayName("writes both the notification and its outbox event")
        void writesOutboxEvent() throws Exception {
            UUID id = UUID.fromString(json(submit(emailBody("priya@example.com"), null))
                    .get("id").asText());

            assertThat(countRows("notifications")).isEqualTo(1);
            assertThat(outboxStatusFor(id)).isNotNull();
        }

        @Test
        @DisplayName("the id is a time-ordered UUID, not a sequential integer")
        void idIsTimeOrdered() throws Exception {
            UUID id = UUID.fromString(json(submit(emailBody("priya@example.com"), null))
                    .get("id").asText());

            assertThat(id.version()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("the asynchronous pipeline")
    class Pipeline {

        @Test
        @DisplayName("a notification is picked up, delivered and recorded")
        void deliversEndToEnd() throws Exception {
            UUID id = UUID.fromString(json(submit(emailBody("success@example.com"), null))
                    .get("id").asText());

            await().atMost(Duration.ofSeconds(25))
                    .pollInterval(Duration.ofMillis(250))
                    .untilAsserted(() -> assertThat(notificationStatus(id)).isEqualTo("SENT"));

            mockMvc.perform(get("/api/v1/notifications/" + id + "/status")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SENT"))
                    .andExpect(jsonPath("$.terminal").value(true))
                    .andExpect(jsonPath("$.attemptCount").value(1))
                    .andExpect(jsonPath("$.attempts.length()").value(1))
                    .andExpect(jsonPath("$.attempts[0].outcome").value("SUCCESS"))
                    .andExpect(jsonPath("$.attempts[0].providerReference").isNotEmpty())
                    .andExpect(jsonPath("$.simulated").value(true));
        }

        /**
         * The timestamps that make "why is this still pending?" answerable.
         */
        @Test
        @DisplayName("processing and completion timestamps are recorded")
        void recordsLifecycleTimestamps() throws Exception {
            UUID id = UUID.fromString(json(submit(emailBody("success@example.com"), null))
                    .get("id").asText());

            await().atMost(Duration.ofSeconds(25))
                    .untilAsserted(() -> assertThat(notificationStatus(id)).isEqualTo("SENT"));

            mockMvc.perform(get("/api/v1/notifications/" + id + "/status")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(jsonPath("$.processingStartedAt").isNotEmpty())
                    .andExpect(jsonPath("$.completedAt").isNotEmpty());
        }

        /**
         * The flaky recipient fails once then succeeds — the fixture that makes
         * the retry path demonstrable rather than merely configured.
         */
        @Test
        @DisplayName("a transient failure is retried and then succeeds, with both attempts recorded")
        void retriesTransientFailure() throws Exception {
            String body = """
                    {"channel":"IN_APP","recipient":"flaky-user-1","body":"retry probe"}""";
            UUID id = UUID.fromString(json(submit(body, null)).get("id").asText());

            await().atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(250))
                    .untilAsserted(() -> assertThat(notificationStatus(id)).isEqualTo("SENT"));

            assertThat(attemptCount(id)).isEqualTo(2);

            mockMvc.perform(get("/api/v1/notifications/" + id + "/status")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(jsonPath("$.attempts[0].outcome").value("TRANSIENT_FAILURE"))
                    .andExpect(jsonPath("$.attempts[1].outcome").value("SUCCESS"))
                    .andExpect(jsonPath("$.attemptCount").value(2));
        }

        @Test
        @DisplayName("retries are exhausted and the notification fails, with every attempt kept")
        void exhaustsRetries() throws Exception {
            String body = """
                    {"channel":"IN_APP","recipient":"transient-user-1","body":"exhaust probe",
                     "maxAttempts":3}""";
            UUID id = UUID.fromString(json(submit(body, null)).get("id").asText());

            await().atMost(Duration.ofSeconds(40))
                    .pollInterval(Duration.ofMillis(400))
                    .untilAsserted(() -> assertThat(notificationStatus(id)).isEqualTo("FAILED"));

            assertThat(attemptCount(id))
                    .as("every attempt must be kept — a single lastError cannot explain a failure")
                    .isEqualTo(3);

            mockMvc.perform(get("/api/v1/notifications/" + id + "/status")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.terminal").value(true))
                    .andExpect(jsonPath("$.lastError")
                            .value(org.hamcrest.Matchers.containsString("Retries exhausted")));
        }

        /**
         * Retrying a permanent failure burns the whole attempt budget
         * re-sending to an address that will never accept it.
         */
        @Test
        @DisplayName("a permanent failure is NOT retried — one attempt, not three")
        void doesNotRetryPermanentFailure() throws Exception {
            String body = """
                    {"channel":"IN_APP","recipient":"permanent-user-1","body":"permanent probe"}""";
            UUID id = UUID.fromString(json(submit(body, null)).get("id").asText());

            await().atMost(Duration.ofSeconds(25))
                    .untilAsserted(() -> assertThat(notificationStatus(id)).isEqualTo("FAILED"));

            assertThat(attemptCount(id)).isEqualTo(1);

            mockMvc.perform(get("/api/v1/notifications/" + id + "/status")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(jsonPath("$.attempts[0].outcome").value("PERMANENT_FAILURE"))
                    .andExpect(jsonPath("$.lastError")
                            .value(org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("Retries exhausted"))));
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("the same key with the same body replays, and creates nothing new")
        void replaysOnRepeat() throws Exception {
            String first = submit(emailBody("priya@example.com"), "key-1");

            mockMvc.perform(post("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(emailBody("priya@example.com")))
                    // 200, not 202: nothing was accepted this time.
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(json(first).get("id").asText()));

            assertThat(countRows("notifications")).isEqualTo(1);
        }

        /**
         * Replaying the first response for a different body would silently
         * swallow a genuine second notification — which, for a password reset,
         * means the user never gets their email.
         */
        @Test
        @DisplayName("the same key with a DIFFERENT body is 409, not a replay")
        void rejectsKeyReuse() throws Exception {
            submit(emailBody("priya@example.com"), "key-1");

            mockMvc.perform(post("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"channel":"IN_APP","recipient":"someone-else","body":"different"}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

            assertThat(countRows("notifications")).isEqualTo(1);
        }

        @Test
        @DisplayName("without a key, a repeat creates a second notification — the caller's choice")
        void noKeyMeansNoProtection() throws Exception {
            submit(emailBody("priya@example.com"), null);
            submit(emailBody("priya@example.com"), null);

            assertThat(countRows("notifications")).isEqualTo(2);
        }

        /**
         * Two callers must be able to choose the same key without colliding; a
         * global key space would let one caller's choice of "1" block everyone.
         */
        @Test
        @DisplayName("keys are scoped per caller")
        void keysAreScopedPerUser() throws Exception {
            String otherToken = userToken("other-caller");

            submit(emailBody("priya@example.com"), "shared-key");

            mockMvc.perform(post("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, otherToken)
                            .header("Idempotency-Key", "shared-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(emailBody("other@example.com")))
                    .andExpect(status().isAccepted());

            assertThat(countRows("notifications")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest(name = "{0} with recipient {1}")
        @CsvSource({
                "SMS,    priya@example.com",
                "SMS,    9876543210",
                "EMAIL,  +919876543210",
                "EMAIL,  not-an-email",
                "IN_APP, 'has space'",
        })
        @DisplayName("a recipient that does not fit the channel is 400")
        void rejectsMismatchedRecipient(String channel, String recipient) throws Exception {
            mockMvc.perform(post("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"channel":"%s","recipient":"%s","subject":"s","body":"b"}"""
                                    .formatted(channel, recipient)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            assertThat(countRows("notifications")).isZero();
        }

        @Test
        @DisplayName("EMAIL without a subject is 400")
        void emailRequiresSubject() throws Exception {
            mockMvc.perform(post("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"channel":"EMAIL","recipient":"a@b.co","body":"b"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[?(@.field == 'subject')]").exists());
        }

        /**
         * A rejected recipient is someone's email address or phone number, and
         * validation bodies end up in logs and screenshots.
         */
        @Test
        @DisplayName("the rejected recipient is redacted in the error body")
        void redactsRejectedRecipient() throws Exception {
            String body = mockMvc.perform(post("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"channel":"SMS","recipient":"priya@example.com","body":"b"}"""))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("priya@example.com").contains("(redacted)");
        }

        @Test
        @DisplayName("an unknown channel lists the valid ones")
        void unknownChannelIsHelpful() throws Exception {
            mockMvc.perform(post("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"channel":"CARRIER_PIGEON","recipient":"a@b.co","body":"b"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.containsString("EMAIL")));
        }

        @Test
        @DisplayName("malformed JSON is 400 and does not echo the body back")
        void malformedJson() throws Exception {
            mockMvc.perform(post("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"channel\":\"EMAIL\",\"body\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }

        @Test
        @DisplayName("maxAttempts is capped")
        void maxAttemptsIsCapped() throws Exception {
            mockMvc.perform(post("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"channel":"IN_APP","recipient":"u","body":"b","maxAttempts":9999}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Nested
    @DisplayName("listing and filtering")
    class Listing {

        @Test
        @DisplayName("filters by channel and status, and paginates")
        void filtersAndPaginates() throws Exception {
            submit(emailBody("a@example.com"), null);
            submit("""
                    {"channel":"SMS","recipient":"+919876543210","body":"sms"}""", null);
            submit("""
                    {"channel":"IN_APP","recipient":"user-1","body":"in-app"}""", null);

            mockMvc.perform(get("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(3));

            mockMvc.perform(get("/api/v1/notifications").param("channel", "SMS")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].channel").value("SMS"));

            mockMvc.perform(get("/api/v1/notifications").param("size", "2")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.first").value(true))
                    .andExpect(jsonPath("$.last").value(false));
        }

        @Test
        @DisplayName("an inverted date range is 400, not a silently empty page")
        void invertedDateRange() throws Exception {
            mockMvc.perform(get("/api/v1/notifications")
                            .param("createdAfter", "2026-10-16T00:00:00Z")
                            .param("createdBefore", "2026-10-15T00:00:00Z")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        }

        @Test
        @DisplayName("an oversized page size is rejected rather than honoured")
        void pageSizeIsCapped() throws Exception {
            mockMvc.perform(get("/api/v1/notifications").param("size", "100000")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("every response carries a trace id the caller can quote")
    void traceIdHeader() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("an error body carries the same trace id as the header")
    void traceIdIsCorrelatable() throws Exception {
        var result = mockMvc.perform(get("/api/v1/notifications/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        String inBody = json(result.getResponse().getContentAsString()).get("traceId").asText();
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo(inBody);
    }
}
