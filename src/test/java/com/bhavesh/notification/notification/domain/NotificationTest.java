package com.bhavesh.notification.notification.domain;

import com.bhavesh.notification.common.exception.ErrorCode;
import com.bhavesh.notification.common.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The notification aggregate: claiming, transitions and the attempt counter. */
class NotificationTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private Notification notification;

    @BeforeEach
    void setUp() {
        notification = Notification.create(
                NotificationChannel.EMAIL, "priya@example.com", "Your code",
                "Your code is 418290.", null, 7L, NotificationPriority.NORMAL, 3, NOW);
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        @DisplayName("starts PENDING with no attempts and no completion")
        void initialState() {
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
            assertThat(notification.getAttemptCount()).isZero();
            assertThat(notification.getProcessingStartedAt()).isNull();
            assertThat(notification.getCompletedAt()).isNull();
            assertThat(notification.getNextAttemptAt()).isNull();
            assertThat(notification.isTerminal()).isFalse();
        }

        /**
         * The id must exist before the insert so the outbox event written in
         * the same transaction can reference it.
         */
        @Test
        @DisplayName("assigns a time-ordered id at construction, not at persist")
        void idIsAssignedImmediately() {
            assertThat(notification.getId()).isNotNull();
            assertThat(notification.getId().version())
                    .as("version 7 — time-ordered, so inserts do not scatter the index")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("ids from successive creations sort in creation order")
        void idsAreTimeOrdered() {
            var earlier = Notification.create(NotificationChannel.IN_APP, "u1", null, "x",
                    null, 7L, null, 3, NOW);
            var later = Notification.create(NotificationChannel.IN_APP, "u2", null, "x",
                    null, 7L, null, 3, NOW.plusSeconds(60));

            assertThat(earlier.getId().toString())
                    .as("a v4 UUID would sort randomly here")
                    .isLessThan(later.getId().toString());
        }

        @Test
        @DisplayName("a null priority defaults to NORMAL rather than failing")
        void nullPriorityDefaults() {
            var n = Notification.create(NotificationChannel.IN_APP, "u", null, "x",
                    null, 7L, null, 3, NOW);
            assertThat(n.getPriority()).isEqualTo(NotificationPriority.NORMAL);
        }
    }

    @Nested
    @DisplayName("claiming")
    class Claiming {

        @Test
        @DisplayName("moves to PROCESSING and returns the new attempt number")
        void claimIncrementsAndReturns() {
            int attempt = notification.beginProcessing(NOW);

            assertThat(attempt).isEqualTo(1);
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
            assertThat(notification.getAttemptCount()).isEqualTo(1);
            assertThat(notification.getProcessingStartedAt()).isEqualTo(NOW);
        }

        /**
         * This is the in-memory half of the duplicate-event defence. A second
         * consumer loading the same row cannot claim it, so it cannot produce a
         * second provider call.
         */
        @Test
        @DisplayName("a second claim while PROCESSING is refused")
        void cannotClaimTwice() {
            notification.beginProcessing(NOW);

            assertThatThrownBy(() -> notification.beginProcessing(NOW))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_STATUS_TRANSITION)
                    .hasMessageContaining("PROCESSING");

            assertThat(notification.getAttemptCount())
                    .as("a refused claim must not consume an attempt")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a terminal notification cannot be claimed")
        void cannotClaimTerminal() {
            notification.beginProcessing(NOW);
            notification.markSent(NOW);

            assertThatThrownBy(() -> notification.beginProcessing(NOW))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        /**
         * The consumer uses this to tell a duplicate event ("already handled,
         * acknowledge quietly") from a genuine bug.
         */
        @Test
        @DisplayName("the exception reports whether the notification had already moved on")
        void exceptionDistinguishesAlreadyHandled() {
            notification.beginProcessing(NOW);

            assertThatThrownBy(() -> notification.beginProcessing(NOW))
                    .isInstanceOfSatisfying(InvalidStateTransitionException.class,
                            e -> assertThat(e.isAlreadyHandled()).isTrue());
        }

        @Test
        @DisplayName("processingStartedAt is set once and not moved by a retry")
        void processingStartedAtIsFirstClaimOnly() {
            notification.beginProcessing(NOW);
            notification.scheduleRetry(NOW.plusSeconds(2), "transient", NOW);

            Instant later = NOW.plus(Duration.ofMinutes(5));
            notification.beginProcessing(later);

            assertThat(notification.getProcessingStartedAt())
                    .as("end-to-end latency is measured from the FIRST pickup")
                    .isEqualTo(NOW);
        }

        @Test
        @DisplayName("claiming clears a pending retry time")
        void claimClearsNextAttemptAt() {
            notification.beginProcessing(NOW);
            notification.scheduleRetry(NOW.plusSeconds(2), "transient", NOW);
            assertThat(notification.getNextAttemptAt()).isNotNull();

            notification.beginProcessing(NOW.plusSeconds(3));
            assertThat(notification.getNextAttemptAt()).isNull();
        }
    }

    @Nested
    @DisplayName("outcomes")
    class Outcomes {

        @BeforeEach
        void claim() {
            notification.beginProcessing(NOW);
        }

        @Test
        void sent() {
            notification.markSent(NOW.plusSeconds(1));

            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(notification.getCompletedAt()).isEqualTo(NOW.plusSeconds(1));
            assertThat(notification.getLastError())
                    .as("a successful delivery clears any error from a previous attempt")
                    .isNull();
            assertThat(notification.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("a scheduled retry is not terminal and records when to try again")
        void retryScheduled() {
            Instant due = NOW.plusSeconds(2);
            notification.scheduleRetry(due, "Provider timed out", NOW);

            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRY_SCHEDULED);
            assertThat(notification.getNextAttemptAt()).isEqualTo(due);
            assertThat(notification.getLastError()).isEqualTo("Provider timed out");
            assertThat(notification.getCompletedAt())
                    .as("not finished — a terminal timestamp here would be wrong")
                    .isNull();
            assertThat(notification.isTerminal()).isFalse();
        }

        @Test
        void failed() {
            notification.markFailed("Recipient rejected", NOW.plusSeconds(1));

            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
            assertThat(notification.getCompletedAt()).isNotNull();
            assertThat(notification.getNextAttemptAt()).isNull();
            assertThat(notification.isTerminal()).isTrue();
        }

        @Test
        void deadLettered() {
            notification.markDeadLettered("Could not be processed", NOW.plusSeconds(1));

            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTERED);
            assertThat(notification.isTerminal()).isTrue();
        }

        /**
         * The CHECK constraint {@code ck_notifications_completed_at} enforces
         * this at the database level too; the entity must not produce a row
         * that would violate it.
         */
        @Test
        @DisplayName("every terminal outcome sets completedAt, and no non-terminal one does")
        void completedAtMatchesTerminality() {
            assertThat(notification.getCompletedAt()).isNull();   // PROCESSING

            notification.scheduleRetry(NOW.plusSeconds(2), "x", NOW);
            assertThat(notification.getCompletedAt()).isNull();   // RETRY_SCHEDULED

            notification.beginProcessing(NOW.plusSeconds(3));
            notification.markFailed("x", NOW.plusSeconds(4));
            assertThat(notification.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("an over-long error is truncated rather than overflowing the column")
        void errorIsTruncated() {
            notification.markFailed("x".repeat(900), NOW.plusSeconds(1));

            assertThat(notification.getLastError())
                    .as("a provider message must never be what fails the write recording it")
                    .hasSize(500)
                    .endsWith("...");
        }
    }

    @Nested
    @DisplayName("attempt budget")
    class Budget {

        @Test
        void hasAttemptsRemainingUntilTheBudgetIsSpent() {
            assertThat(notification.hasAttemptsRemaining()).isTrue();

            for (int i = 1; i <= 2; i++) {
                notification.beginProcessing(NOW);
                notification.scheduleRetry(NOW.plusSeconds(2), "transient", NOW);
                assertThat(notification.hasAttemptsRemaining())
                        .as("after attempt %d of 3", i).isTrue();
            }

            notification.beginProcessing(NOW);
            assertThat(notification.getAttemptCount()).isEqualTo(3);
            assertThat(notification.hasAttemptsRemaining()).isFalse();
        }
    }

    @Nested
    @DisplayName("visibility and safety")
    class Safety {

        @Test
        @DisplayName("the owner sees it; another caller does not; an administrator does")
        void visibility() {
            assertThat(notification.isVisibleTo(7L, false)).isTrue();
            assertThat(notification.isVisibleTo(99L, false)).isFalse();
            assertThat(notification.isVisibleTo(99L, true)).isTrue();
        }

        /**
         * A notification body routinely contains a one-time code or a
         * password-reset link, and {@code toString} lands in exception messages
         * and log lines constantly.
         */
        @Test
        @DisplayName("toString leaks neither the body, the subject nor the full recipient")
        void toStringIsSafeForLogging() {
            String text = notification.toString();

            assertThat(text)
                    .doesNotContain("418290")
                    .doesNotContain("Your code is")
                    .doesNotContain("priya@example.com");
            assertThat(text)
                    .as("still useful: masked recipient, channel and status")
                    .contains("p***a@example.com")
                    .contains("EMAIL")
                    .contains("PENDING");
        }

        @Test
        @DisplayName("the masked recipient keeps the domain but hides the mailbox")
        void recipientIsMasked() {
            assertThat(notification.getMaskedRecipient()).isEqualTo("p***a@example.com");
        }
    }
}
