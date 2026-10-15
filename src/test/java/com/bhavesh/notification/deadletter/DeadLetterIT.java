package com.bhavesh.notification.deadletter;

import com.bhavesh.notification.deadletter.domain.DeadLetterStatus;
import com.bhavesh.notification.deadletter.service.DeadLetterService;
import com.bhavesh.notification.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dead-letter recording, inspection and replay.
 *
 * <p>A dead letter is a <em>processing</em> failure — an unparseable payload,
 * an event referencing a notification that does not exist. It is deliberately
 * distinct from a notification a provider declined, which is a FAILED
 * notification with a full attempt history and means nothing is broken.
 */
class DeadLetterIT extends AbstractIntegrationTest {

    @Autowired
    private DeadLetterService deadLetterService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = adminToken("dlq-operator");
        userToken = userToken("dlq-ordinary");
    }

    private ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("notifications.test.requested.v1", 0, 42L, "some-key", payload);
    }

    @Nested
    @DisplayName("recording")
    class Recording {

        @Test
        @DisplayName("stores the source coordinates so a replay can be targeted")
        void storesSourceCoordinates() throws Exception {
            deadLetterService.record(record("{not valid json"),
                    "Event payload could not be parsed", "JsonParseException", null);

            mockMvc.perform(get("/api/v1/admin/dead-letters")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].sourceTopic")
                            .value("notifications.test.requested.v1"))
                    .andExpect(jsonPath("$.content[0].sourcePartition").value(0))
                    .andExpect(jsonPath("$.content[0].sourceOffset").value(42))
                    .andExpect(jsonPath("$.content[0].status").value("UNRESOLVED"));
        }

        /**
         * A {@code jsonb} column would reject an unparseable payload on insert
         * — losing the evidence at the exact moment it matters most.
         */
        @Test
        @DisplayName("an unparseable payload is stored as-is, not rejected")
        void storesUnparseablePayload() throws Exception {
            deadLetterService.record(record("{this is not json at all"),
                    "Could not parse", "JsonParseException", null);

            mockMvc.perform(get("/api/v1/admin/dead-letters")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(jsonPath("$.content[0].payload")
                            .value(org.hamcrest.Matchers.containsString("not json")));
        }

        @Test
        @DisplayName("an oversized payload is truncated rather than filling the database")
        void truncatesHugePayloads() throws Exception {
            deadLetterService.record(record("x".repeat(50_000)),
                    "Too big", null, null);

            String body = mockMvc.perform(get("/api/v1/admin/dead-letters")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andReturn().getResponse().getContentAsString();

            String payload = json(body).get("content").get(0).get("payload").asText();
            assertThat(payload).hasSizeLessThan(9_000).endsWith("[truncated]");
        }

        @Test
        @DisplayName("the unresolved count is the number to alert on")
        void unresolvedCount() throws Exception {
            assertThat(deadLetterService.unresolvedCount()).isZero();

            deadLetterService.record(record("{}"), "reason one", null, null);
            deadLetterService.record(record("{}"), "reason two", null, null);

            assertThat(deadLetterService.unresolvedCount()).isEqualTo(2);

            mockMvc.perform(get("/api/v1/admin/dead-letters/count")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(jsonPath("$.unresolved").value(2));
        }
    }

    @Nested
    @DisplayName("replay")
    class Replay {

        @Test
        @DisplayName("an event with a notification id can be replayed and is marked REPLAYED")
        void replaysAndMarks() throws Exception {
            UUID notificationId = UUID.randomUUID();
            deadLetterService.record(record("{}"), "transient pipeline fault", null, notificationId);

            String list = mockMvc.perform(get("/api/v1/admin/dead-letters")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andReturn().getResponse().getContentAsString();
            UUID dlqId = UUID.fromString(json(list).get("content").get(0).get("id").asText());

            mockMvc.perform(post("/api/v1/admin/dead-letters/" + dlqId + "/replay")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REPLAYED"))
                    .andExpect(jsonPath("$.replayedAt").isNotEmpty())
                    .andExpect(jsonPath("$.replayedBy").isNotEmpty());

            assertThat(deadLetterService.unresolvedCount()).isZero();
        }

        /**
         * Without this, two operators working the same backlog — or one
         * operator double-clicking — would each enqueue the notification again.
         */
        @Test
        @DisplayName("replaying twice is refused")
        void cannotReplayTwice() throws Exception {
            UUID notificationId = UUID.randomUUID();
            deadLetterService.record(record("{}"), "fault", null, notificationId);

            String list = mockMvc.perform(get("/api/v1/admin/dead-letters")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andReturn().getResponse().getContentAsString();
            UUID dlqId = UUID.fromString(json(list).get("content").get(0).get("id").asText());

            mockMvc.perform(post("/api/v1/admin/dead-letters/" + dlqId + "/replay")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/admin/dead-letters/" + dlqId + "/replay")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_REPLAYABLE"));
        }

        /**
         * An unparseable payload has nothing to replay <em>to</em>; the right
         * action is to fix the producer and discard it.
         */
        @Test
        @DisplayName("an event with no notification id cannot be replayed")
        void cannotReplayWithoutATarget() throws Exception {
            deadLetterService.record(record("{garbage"), "unparseable", null, null);

            String list = mockMvc.perform(get("/api/v1/admin/dead-letters")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andReturn().getResponse().getContentAsString();
            UUID dlqId = UUID.fromString(json(list).get("content").get(0).get("id").asText());

            mockMvc.perform(post("/api/v1/admin/dead-letters/" + dlqId + "/replay")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.containsString("nothing to replay")));
        }

        @Test
        @DisplayName("an unrecoverable event can be discarded instead")
        void discard() throws Exception {
            deadLetterService.record(record("{garbage"), "unparseable", null, null);

            String list = mockMvc.perform(get("/api/v1/admin/dead-letters")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andReturn().getResponse().getContentAsString();
            UUID dlqId = UUID.fromString(json(list).get("content").get(0).get("id").asText());

            mockMvc.perform(post("/api/v1/admin/dead-letters/" + dlqId + "/discard")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DISCARDED"));

            assertThat(deadLetterService.unresolvedCount())
                    .as("the record is kept; only its status changes")
                    .isZero();
            assertThat(countRows("dead_letter_events")).isEqualTo(1);
        }

        @Test
        @DisplayName("an unknown id is 404")
        void unknownId() throws Exception {
            mockMvc.perform(post("/api/v1/admin/dead-letters/" + UUID.randomUUID() + "/replay")
                            .header(HttpHeaders.AUTHORIZATION, adminToken))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("an ordinary caller cannot see the dead-letter queue")
        void ordinaryCallerIsForbidden() throws Exception {
            deadLetterService.record(record("{}"), "fault", null, null);

            mockMvc.perform(get("/api/v1/admin/dead-letters")
                            .header(HttpHeaders.AUTHORIZATION, userToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an ordinary caller cannot replay")
        void ordinaryCallerCannotReplay() throws Exception {
            mockMvc.perform(post("/api/v1/admin/dead-letters/" + UUID.randomUUID() + "/replay")
                            .header(HttpHeaders.AUTHORIZATION, userToken))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * End to end: a genuinely malformed message on the real topic should be
     * consumed, found unparseable and recorded — without the consumer dying or
     * the message being retried forever.
     */
    @Test
    @DisplayName("a malformed message on the real topic is dead-lettered, not retried forever")
    void malformedMessageIsDeadLettered() {
        kafkaTemplate.send("notifications.test.requested.v1", "bad-key", "{this is not json");

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() ->
                        assertThat(deadLetterService.unresolvedCount()).isGreaterThanOrEqualTo(1));

        assertThat(countRows("notifications"))
                .as("a malformed event must not create a notification")
                .isZero();
    }

    /**
     * An event naming a notification that does not exist is a pipeline fault,
     * not a delivery failure — retrying cannot conjure the row into existence.
     */
    @Test
    @DisplayName("an event for a non-existent notification is dead-lettered")
    void missingNotificationIsDeadLettered() throws Exception {
        UUID ghost = UUID.randomUUID();
        String payload = objectMapper.writeValueAsString(
                new com.bhavesh.notification.notification.service.NotificationService
                        .NotificationRequestedEvent(ghost, "IN_APP", "NORMAL", java.time.Instant.now()));

        kafkaTemplate.send("notifications.test.requested.v1", ghost.toString(), payload);

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    assertThat(deadLetterService.unresolvedCount()).isGreaterThanOrEqualTo(1);
                    assertThat(countRows("dead_letter_events")).isGreaterThanOrEqualTo(1);
                });
    }
}
