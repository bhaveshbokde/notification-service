package com.bhavesh.notification.notification.service;

import com.bhavesh.notification.common.exception.ErrorCode;
import com.bhavesh.notification.common.exception.ResourceNotFoundException;
import com.bhavesh.notification.common.exception.ValidationException;
import com.bhavesh.notification.config.DeliveryProperties;
import com.bhavesh.notification.config.KafkaProperties;
import com.bhavesh.notification.config.ProviderProperties;
import com.bhavesh.notification.notification.domain.Notification;
import com.bhavesh.notification.notification.domain.NotificationChannel;
import com.bhavesh.notification.notification.domain.NotificationPriority;
import com.bhavesh.notification.notification.dto.CreateNotificationRequest;
import com.bhavesh.notification.notification.dto.NotificationResponse;
import com.bhavesh.notification.notification.repository.NotificationAttemptRepository;
import com.bhavesh.notification.notification.repository.NotificationRepository;
import com.bhavesh.notification.outbox.domain.OutboxEvent;
import com.bhavesh.notification.outbox.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Accepting notifications.
 *
 * <p>The central assertion here is that the notification and its outbox event
 * are written together. A mock cannot prove they share a <em>transaction</em> —
 * that is what {@code OutboxTransactionIT} does against a real database — but
 * it can prove the service writes both and does so before returning, which is
 * the contract this layer owns.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Long USER_ID = 7L;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationAttemptRepository attemptRepository;

    @Mock
    private OutboxEventRepository outboxRepository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(
                notificationRepository,
                attemptRepository,
                outboxRepository,
                new KafkaProperties("notifications.requested.v1",
                        "notifications.requested.v1.retry",
                        "notifications.requested.v1.DLT", 3, (short) 1),
                new DeliveryProperties(3, Duration.ofSeconds(2), 2.0, Duration.ofMinutes(1), 0.0),
                new ProviderProperties(true, Duration.ZERO, 0.0),
                testObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Mirrors the mapper Spring Boot auto-configures.
     *
     * <p>A bare {@code new ObjectMapper()} cannot serialise {@link Instant} —
     * it throws "Java 8 date/time type not supported by default" — so a test
     * using one would exercise a configuration that never exists in
     * production, and would fail for a reason the application does not have.
     */
    private static ObjectMapper testObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static CreateNotificationRequest emailRequest() {
        return new CreateNotificationRequest(NotificationChannel.EMAIL, "priya@example.com",
                "Your code", "Your code is 418290.", NotificationPriority.HIGH,
                Map.of("template", "otp-v2"), null);
    }

    @Nested
    @DisplayName("create")
    class Create {

        /**
         * The whole reason the outbox exists. If only one of these two writes
         * happened, the system would either lose a notification or publish an
         * event for one that does not exist.
         */
        @Test
        @DisplayName("writes the notification AND the outbox event")
        void writesBoth() {
            service.create(emailRequest(), USER_ID);

            verify(notificationRepository).save(any(Notification.class));
            verify(outboxRepository).save(any(OutboxEvent.class));
        }

        @Test
        @DisplayName("the outbox event references the notification that was just created")
        void outboxReferencesTheNotification() {
            var notificationCaptor = ArgumentCaptor.forClass(Notification.class);
            var outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

            service.create(emailRequest(), USER_ID);

            verify(notificationRepository).save(notificationCaptor.capture());
            verify(outboxRepository).save(outboxCaptor.capture());

            UUID notificationId = notificationCaptor.getValue().getId();
            assertThat(outboxCaptor.getValue().getAggregateId()).isEqualTo(notificationId);
            assertThat(outboxCaptor.getValue().getMessageKey())
                    .as("keyed by notification id, so events for one notification keep their order")
                    .isEqualTo(notificationId.toString());
        }

        @Test
        @DisplayName("the notification is saved before the outbox event")
        void savesInOrder() {
            service.create(emailRequest(), USER_ID);

            InOrder order = inOrder(notificationRepository, outboxRepository);
            order.verify(notificationRepository).save(any(Notification.class));
            order.verify(outboxRepository).save(any(OutboxEvent.class));
        }

        /**
         * Publishing here would be the exact bug the outbox prevents: the send
         * is asynchronous, so the method could commit and return 202 for an
         * event that never reaches the broker.
         */
        @Test
        @DisplayName("does NOT publish to Kafka — that is the publisher's job")
        void doesNotPublishDirectly() {
            service.create(emailRequest(), USER_ID);

            // The service has no KafkaTemplate at all. This asserts the shape of
            // the collaboration: only repositories are touched.
            verify(notificationRepository).save(any());
            verify(outboxRepository).save(any());
            verify(attemptRepository, never()).save(any());
        }

        @Test
        @DisplayName("returns 'accepted', not 'delivered' — PENDING with no attempts")
        void returnsPending() {
            NotificationResponse response = service.create(emailRequest(), USER_ID);

            assertThat(response.status())
                    .isEqualTo(com.bhavesh.notification.notification.domain.NotificationStatus.PENDING);
            assertThat(response.attemptCount()).isZero();
            assertThat(response.completedAt()).isNull();
        }

        @Test
        @DisplayName("the response masks the recipient and omits the body entirely")
        void responseDoesNotLeakContent() {
            NotificationResponse response = service.create(emailRequest(), USER_ID);

            assertThat(response.recipient()).isEqualTo("p***a@example.com");
            // There is no body accessor on the response at all — the record has
            // no such component, which is the strongest form of this guarantee.
            assertThat(response.toString()).doesNotContain("418290");
        }

        @Test
        @DisplayName("says plainly that the delivery is simulated")
        void reportsSimulation() {
            assertThat(service.create(emailRequest(), USER_ID).simulated()).isTrue();
        }

        @Test
        @DisplayName("the caller's maxAttempts overrides the configured default")
        void perRequestAttemptBudget() {
            var request = new CreateNotificationRequest(NotificationChannel.IN_APP, "user-1",
                    null, "body", null, null, 7);

            assertThat(service.create(request, USER_ID).maxAttempts()).isEqualTo(7);
        }

        @Test
        @DisplayName("without an override the configured default applies")
        void defaultAttemptBudget() {
            var request = new CreateNotificationRequest(NotificationChannel.IN_APP, "user-1",
                    null, "body", null, null, null);

            assertThat(service.create(request, USER_ID).maxAttempts()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("per-channel validation")
    class ChannelValidation {

        @Test
        @DisplayName("an email address is rejected for SMS")
        void wrongRecipientFormat() {
            var request = new CreateNotificationRequest(NotificationChannel.SMS,
                    "priya@example.com", null, "body", null, null, null);

            assertThatThrownBy(() -> service.create(request, USER_ID))
                    .isInstanceOf(ValidationException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED);

            verify(notificationRepository, never()).save(any());
            verify(outboxRepository, never()).save(any());
        }

        @Test
        @DisplayName("EMAIL without a subject is rejected")
        void missingSubjectForEmail() {
            var request = new CreateNotificationRequest(NotificationChannel.EMAIL,
                    "priya@example.com", null, "body", null, null, null);

            assertThatThrownBy(() -> service.create(request, USER_ID))
                    .isInstanceOfSatisfying(ValidationException.class, e ->
                            assertThat(e.violations())
                                    .anySatisfy(v -> assertThat(v.field()).isEqualTo("subject")));
        }

        /**
         * Returning one error at a time makes a caller fix-and-retry in a loop
         * for a request with two problems.
         */
        @Test
        @DisplayName("reports every violation at once, not the first one")
        void reportsAllViolations() {
            var request = new CreateNotificationRequest(NotificationChannel.EMAIL,
                    "not-an-email", null, "body", null, null, null);

            assertThatThrownBy(() -> service.create(request, USER_ID))
                    .isInstanceOfSatisfying(ValidationException.class, e ->
                            assertThat(e.violations())
                                    .as("both the bad recipient and the missing subject")
                                    .hasSize(2));
        }

        /**
         * Validation error bodies end up in logs and screenshots, and a
         * rejected recipient is someone's email address or phone number.
         */
        @Test
        @DisplayName("the rejected recipient is redacted in the violation")
        void rejectedRecipientIsRedacted() {
            var request = new CreateNotificationRequest(NotificationChannel.SMS,
                    "priya@example.com", null, "body", null, null, null);

            assertThatThrownBy(() -> service.create(request, USER_ID))
                    .isInstanceOfSatisfying(ValidationException.class, e ->
                            assertThat(e.violations())
                                    .filteredOn(v -> "recipient".equals(v.field()))
                                    .allSatisfy(v -> assertThat(v.rejectedValue())
                                            .isEqualTo("(redacted)")));
        }

        @Test
        @DisplayName("SMS and IN_APP store no subject even if one is supplied")
        void subjectDroppedWhereMeaningless() {
            var request = new CreateNotificationRequest(NotificationChannel.SMS,
                    "+919876543210", "ignored subject", "body", null, null, null);

            assertThat(service.create(request, USER_ID).subject()).isNull();
        }
    }

    @Nested
    @DisplayName("ownership scoping")
    class Scoping {

        /**
         * The scoping is in the query, not a check on an already-loaded row, so
         * "missing" and "not yours" are genuinely indistinguishable.
         */
        @Test
        @DisplayName("an ordinary caller's read is scoped by createdBy in the query")
        void nonAdminReadIsScoped() {
            UUID id = UUID.randomUUID();
            when(notificationRepository.findByIdAndCreatedBy(id, USER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(id, USER_ID, false))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

            verify(notificationRepository).findByIdAndCreatedBy(id, USER_ID);
            verify(notificationRepository, never()).findByIdUnscoped(any());
        }

        @Test
        @DisplayName("an administrator's read is deliberately unscoped")
        void adminReadIsUnscoped() {
            UUID id = UUID.randomUUID();
            Notification n = Notification.create(NotificationChannel.IN_APP, "user-1", null,
                    "body", null, 99L, null, 3, NOW);
            when(notificationRepository.findByIdUnscoped(id)).thenReturn(Optional.of(n));

            assertThat(service.get(id, USER_ID, true)).isNotNull();

            verify(notificationRepository).findByIdUnscoped(id);
            verify(notificationRepository, never()).findByIdAndCreatedBy(any(), any());
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        @DisplayName("an inverted date range is 400, not a silently empty page")
        void invertedDateRange() {
            assertThatThrownBy(() -> service.list(USER_ID, false, null, null,
                    NOW.plusSeconds(60), NOW,
                    org.springframework.data.domain.PageRequest.of(0, 20)))
                    .isInstanceOf(com.bhavesh.notification.common.exception.ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
        }

        @Test
        @DisplayName("an ordinary caller gets the scoped query, an administrator the unscoped one")
        void listingRespectsRole() {
            var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
            var empty = new org.springframework.data.domain.PageImpl<Notification>(java.util.List.of());

            when(notificationRepository.search(any(), any(), any(), any(), any(), any()))
                    .thenReturn(empty);
            when(notificationRepository.searchAll(any(), any(), any(), any(), any()))
                    .thenReturn(empty);

            service.list(USER_ID, false, null, null, null, null, pageable);
            verify(notificationRepository).search(any(), any(), any(), any(), any(), any());

            service.list(USER_ID, true, null, null, null, null, pageable);
            verify(notificationRepository).searchAll(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("the Kafka event payload")
    class EventPayload {

        /**
         * A Kafka topic is replicated, retained for days and readable by
         * anything with the right ACL — a poor place for a one-time code.
         */
        @Test
        @DisplayName("carries no recipient and no body")
        void payloadCarriesNoContent() {
            var captor = ArgumentCaptor.forClass(OutboxEvent.class);
            service.create(emailRequest(), USER_ID);
            verify(outboxRepository).save(captor.capture());

            String payload = captor.getValue().getPayload();

            assertThat(payload)
                    .doesNotContain("priya@example.com")
                    .doesNotContain("418290")
                    .doesNotContain("Your code is");
            assertThat(payload)
                    .as("enough to identify the target, and nothing more")
                    .contains("notificationId")
                    .contains("EMAIL");
        }

        @Test
        @DisplayName("goes to the configured main topic")
        void targetsTheMainTopic() {
            var captor = ArgumentCaptor.forClass(OutboxEvent.class);
            service.create(emailRequest(), USER_ID);
            verify(outboxRepository).save(captor.capture());

            assertThat(captor.getValue().getTopic()).isEqualTo("notifications.requested.v1");
            assertThat(captor.getValue().getEventType())
                    .isEqualTo(NotificationService.EVENT_NOTIFICATION_REQUESTED);
        }
    }
}
