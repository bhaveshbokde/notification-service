package com.bhavesh.notification.notification.service;

import com.bhavesh.notification.common.dto.PageResponse;
import com.bhavesh.notification.common.exception.ApiException;
import com.bhavesh.notification.common.exception.ErrorCode;
import com.bhavesh.notification.common.exception.ProblemDetailFactory.FieldViolation;
import com.bhavesh.notification.common.exception.ResourceNotFoundException;
import com.bhavesh.notification.common.exception.ValidationException;
import com.bhavesh.notification.config.DeliveryProperties;
import com.bhavesh.notification.config.KafkaProperties;
import com.bhavesh.notification.config.ProviderProperties;
import com.bhavesh.notification.notification.domain.Notification;
import com.bhavesh.notification.notification.domain.NotificationChannel;
import com.bhavesh.notification.notification.domain.NotificationStatus;
import com.bhavesh.notification.notification.dto.AttemptResponse;
import com.bhavesh.notification.notification.dto.CreateNotificationRequest;
import com.bhavesh.notification.notification.dto.NotificationResponse;
import com.bhavesh.notification.notification.dto.NotificationStatusResponse;
import com.bhavesh.notification.notification.repository.NotificationAttemptRepository;
import com.bhavesh.notification.notification.repository.NotificationRepository;
import com.bhavesh.notification.outbox.domain.OutboxEvent;
import com.bhavesh.notification.outbox.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Accepting notifications, and reading them back.
 *
 * <h2>The transaction boundary is the whole point of this class</h2>
 * {@link #create} writes the notification <em>and</em> its outbox event in one
 * transaction. Both commit or neither does, so it is impossible to end up with
 * a notification nobody will ever process, or an event referencing a
 * notification that does not exist.
 *
 * <p>What this method deliberately does <strong>not</strong> do is call Kafka.
 * {@code kafkaTemplate.send()} here would be the bug the outbox exists to
 * prevent: the send is asynchronous and the broker may be down, so the method
 * could commit and return 202 for an event that is then lost. Publishing is the
 * {@code OutboxPublisher}'s job, and it marks an event published only after the
 * broker acknowledges.
 *
 * <p>The same rule explains why no provider call happens here. Holding a
 * database transaction open across a network call to an email gateway would
 * pin a connection for the duration of someone else's outage.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Event type written into the outbox; part of the Kafka payload contract. */
    public static final String EVENT_NOTIFICATION_REQUESTED = "NotificationRequested";

    private final NotificationRepository notificationRepository;
    private final NotificationAttemptRepository attemptRepository;
    private final OutboxEventRepository outboxRepository;
    private final KafkaProperties kafkaProperties;
    private final DeliveryProperties deliveryProperties;
    private final ProviderProperties providerProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationAttemptRepository attemptRepository,
                               OutboxEventRepository outboxRepository,
                               KafkaProperties kafkaProperties,
                               DeliveryProperties deliveryProperties,
                               ProviderProperties providerProperties,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.notificationRepository = notificationRepository;
        this.attemptRepository = attemptRepository;
        this.outboxRepository = outboxRepository;
        this.kafkaProperties = kafkaProperties;
        this.deliveryProperties = deliveryProperties;
        this.providerProperties = providerProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- create --

    /**
     * Accepts a notification for delivery.
     *
     * <p>Returns as soon as the request is durably recorded. The caller gets
     * 202 Accepted, not 201 Created: nothing has been delivered yet, and
     * claiming otherwise would be a lie the client might act on.
     */
    @Transactional
    public NotificationResponse create(CreateNotificationRequest request, Long userId) {
        validateForChannel(request);

        Instant now = clock.instant();
        int maxAttempts = request.maxAttempts() != null
                ? request.maxAttempts()
                : deliveryProperties.maxAttempts();

        Notification notification = Notification.create(
                request.channel(),
                request.recipient().trim(),
                normaliseSubject(request),
                request.body(),
                serialiseMetadata(request),
                userId,
                request.priority(),
                maxAttempts,
                now);

        notificationRepository.save(notification);

        // THE CRITICAL LINE. Same transaction as the save above. If the
        // process dies between these two statements, neither is committed.
        outboxRepository.save(OutboxEvent.forNotification(
                notification.getId(),
                EVENT_NOTIFICATION_REQUESTED,
                kafkaProperties.topic(),
                buildEventPayload(notification),
                now));

        log.info("Accepted notification {} channel={} recipient={} priority={}",
                notification.getId(), notification.getChannel(),
                notification.getMaskedRecipient(), notification.getPriority());

        return NotificationResponse.from(notification, providerProperties.simulationEnabled());
    }

    /**
     * Cross-field validation that Bean Validation cannot express.
     *
     * <p>{@code @Email} on the recipient would be wrong — it is only an email
     * address when the channel is EMAIL. The per-channel rules live on
     * {@link NotificationChannel}, so this method asks rather than reimplements.
     *
     * <p>Collects every violation before throwing. Returning one error at a
     * time makes a caller fix-and-retry in a loop for a request with two
     * problems.
     */
    private void validateForChannel(CreateNotificationRequest request) {
        List<FieldViolation> violations = new ArrayList<>(2);

        if (!request.channel().isValidRecipient(request.recipient())) {
            violations.add(FieldViolation.of("recipient", request.recipient(),
                    "must be %s for channel %s"
                            .formatted(request.channel().recipientDescription(), request.channel())));
        }

        if (request.channel().isSubjectRequired()
                && (request.subject() == null || request.subject().isBlank())) {
            violations.add(FieldViolation.of("subject", null,
                    "is required for channel " + request.channel()));
        }

        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }

    /** SMS and IN_APP have no subject; storing one would be misleading noise. */
    private String normaliseSubject(CreateNotificationRequest request) {
        if (!request.channel().isSubjectRequired()) {
            return null;
        }
        return request.subject() == null ? null : request.subject().trim();
    }

    private String serialiseMetadata(CreateNotificationRequest request) {
        if (request.metadata() == null || request.metadata().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(request.metadata());
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "metadata could not be serialised", e);
        }
    }

    /**
     * The Kafka event payload.
     *
     * <p>Deliberately thin: the notification id, the channel and a timestamp.
     * It carries <strong>no recipient and no body</strong>, for two reasons.
     *
     * <p>The first is privacy — a Kafka topic is replicated, retained for days
     * and readable by anything with the right ACL, which is a poor place for
     * one-time codes and password-reset links.
     *
     * <p>The second is correctness. The consumer re-reads the notification row
     * rather than trusting the payload, so a duplicate or stale event cannot
     * cause it to act on out-of-date content. Putting the body in the event
     * would invite exactly that mistake.
     */
    private String buildEventPayload(Notification notification) {
        try {
            return objectMapper.writeValueAsString(new NotificationRequestedEvent(
                    notification.getId(),
                    notification.getChannel().name(),
                    notification.getPriority().name(),
                    notification.getCreatedAt()));
        } catch (JsonProcessingException e) {
            // Unreachable for this fixed shape, but failing loudly beats
            // writing an event that cannot be parsed later.
            throw new IllegalStateException("Could not serialise outbox payload", e);
        }
    }

    /** The on-the-wire event contract. */
    public record NotificationRequestedEvent(UUID notificationId, String channel,
                                             String priority, Instant requestedAt) {
    }

    // ----------------------------------------------------------------- reads --

    /**
     * Ownership-scoped read.
     *
     * <p>A miss means "not found or not yours", and answers 404 either way — a
     * 403 would confirm the id exists.
     */
    @Transactional(readOnly = true)
    public NotificationResponse get(UUID id, Long userId, boolean admin) {
        return NotificationResponse.from(requireVisible(id, userId, admin),
                providerProperties.simulationEnabled());
    }

    /**
     * Delivery status with the full attempt history.
     *
     * <p>Two queries: the notification, then its attempts. Not a fetch join —
     * this is a single aggregate, so the second query is bounded by
     * {@code maxAttempts} and the join would duplicate the parent row for every
     * attempt.
     */
    @Transactional(readOnly = true)
    public NotificationStatusResponse getStatus(UUID id, Long userId, boolean admin) {
        Notification notification = requireVisible(id, userId, admin);

        List<AttemptResponse> attempts =
                attemptRepository.findByNotificationIdOrderByAttemptNumberAsc(id).stream()
                        .map(AttemptResponse::from)
                        .toList();

        return NotificationStatusResponse.from(notification, attempts,
                providerProperties.simulationEnabled());
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(Long userId, boolean admin,
                                                   NotificationStatus status,
                                                   NotificationChannel channel,
                                                   Instant createdAfter, Instant createdBefore,
                                                   Pageable pageable) {
        if (createdAfter != null && createdBefore != null && createdAfter.isAfter(createdBefore)) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER,
                    "createdAfter must not be later than createdBefore");
        }

        var page = admin
                ? notificationRepository.searchAll(status, channel, createdAfter, createdBefore, pageable)
                : notificationRepository.search(userId, status, channel, createdAfter, createdBefore, pageable);

        boolean simulated = providerProperties.simulationEnabled();
        return PageResponse.from(page, n -> NotificationResponse.from(n, simulated));
    }

    /**
     * Loads a notification the caller is allowed to see.
     *
     * <p>An administrator read is unscoped; an ordinary caller's is scoped in
     * the query itself, so the two cases are genuinely indistinguishable rather
     * than filtered afterwards.
     */
    private Notification requireVisible(UUID id, Long userId, boolean admin) {
        return (admin
                ? notificationRepository.findByIdUnscoped(id)
                : notificationRepository.findByIdAndCreatedBy(id, userId))
                .orElseThrow(() -> new ResourceNotFoundException("Notification", id));
    }
}
