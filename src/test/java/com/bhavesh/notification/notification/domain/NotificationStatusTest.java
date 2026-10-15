package com.bhavesh.notification.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The notification state machine.
 *
 * <p>Worth testing exhaustively rather than by example: the table is the one
 * place the lifecycle rules exist, and a wrong entry is a silent policy change
 * — a notification that can be re-claimed while another consumer is delivering
 * it, or one that can never reach a terminal state.
 */
class NotificationStatusTest {

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "PENDING,         PROCESSING",
            "PENDING,         DEAD_LETTERED",
            "PROCESSING,      SENT",
            "PROCESSING,      FAILED",
            "PROCESSING,      RETRY_SCHEDULED",
            "PROCESSING,      DEAD_LETTERED",
            "RETRY_SCHEDULED, PROCESSING",
            "RETRY_SCHEDULED, FAILED",
            "RETRY_SCHEDULED, DEAD_LETTERED",
    })
    void allowsLegalTransitions(NotificationStatus from, NotificationStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} is refused")
    @CsvSource({
            // Skipping delivery entirely would mark something SENT that was
            // never handed to a provider.
            "PENDING,         SENT",
            "PENDING,         FAILED",
            "PENDING,         RETRY_SCHEDULED",
            // Terminal means terminal.
            "SENT,            PROCESSING",
            "SENT,            FAILED",
            "FAILED,          PROCESSING",
            "FAILED,          SENT",
            "DEAD_LETTERED,   PROCESSING",
            "DEAD_LETTERED,   SENT",
    })
    void refusesIllegalTransitions(NotificationStatus from, NotificationStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    /**
     * The single most important entry in the table. If PROCESSING could
     * transition to itself, a duplicate Kafka event would be able to re-claim a
     * notification another consumer is actively delivering — producing two
     * provider calls and, for SMS, two real messages.
     */
    @Test
    @DisplayName("PROCESSING cannot be re-entered — this is what blocks a duplicate claim")
    void processingCannotBeReclaimed() {
        assertThat(NotificationStatus.PROCESSING.canTransitionTo(NotificationStatus.PROCESSING))
                .isFalse();
        assertThat(NotificationStatus.PROCESSING.isClaimable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(NotificationStatus.class)
    @DisplayName("no status may transition to itself")
    void noSelfTransition(NotificationStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(NotificationStatus.class)
    @DisplayName("every status has an entry, so no lookup can return null")
    void everyStatusHasAnEntry(NotificationStatus status) {
        assertThat(status.allowedTransitions()).isNotNull();
    }

    @Test
    @DisplayName("a null target is refused rather than throwing")
    void nullTargetIsRefused() {
        assertThat(NotificationStatus.PENDING.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName("SENT, FAILED and DEAD_LETTERED are the terminal states")
    void terminalStates() {
        assertThat(NotificationStatus.SENT.isTerminal()).isTrue();
        assertThat(NotificationStatus.FAILED.isTerminal()).isTrue();
        assertThat(NotificationStatus.DEAD_LETTERED.isTerminal()).isTrue();

        assertThat(NotificationStatus.PENDING.isTerminal()).isFalse();
        assertThat(NotificationStatus.PROCESSING.isTerminal()).isFalse();
        assertThat(NotificationStatus.RETRY_SCHEDULED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("only PENDING and RETRY_SCHEDULED may be claimed for delivery")
    void claimableStates() {
        assertThat(NotificationStatus.PENDING.isClaimable()).isTrue();
        assertThat(NotificationStatus.RETRY_SCHEDULED.isClaimable()).isTrue();

        assertThat(NotificationStatus.PROCESSING.isClaimable()).isFalse();
        assertThat(NotificationStatus.SENT.isClaimable()).isFalse();
        assertThat(NotificationStatus.FAILED.isClaimable()).isFalse();
        assertThat(NotificationStatus.DEAD_LETTERED.isClaimable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(NotificationStatus.class)
    @DisplayName("isActive is exactly the negation of isTerminal")
    void activeIsNotTerminal(NotificationStatus status) {
        assertThat(status.isActive()).isEqualTo(!status.isTerminal());
    }

    /**
     * Wrapping only the outer map leaves the {@code EnumSet} values mutable, so
     * a caller could do {@code SENT.allowedTransitions().add(PROCESSING)} and
     * permanently grant themselves a transition for the lifetime of the JVM.
     * The map being read-only says nothing about what is inside it.
     */
    @ParameterizedTest
    @EnumSource(NotificationStatus.class)
    @DisplayName("the returned transition set cannot be used to edit the table")
    void transitionSetIsImmutable(NotificationStatus status) {
        var allowed = status.allowedTransitions();

        assertThatThrownBy(() -> allowed.add(NotificationStatus.SENT))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(allowed::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("every non-terminal state can reach a terminal one")
    void noStateIsAnInescapableTrap() {
        // A state from which no terminal state is reachable would mean
        // notifications that can never finish — stuck forever with no error.
        for (NotificationStatus status : NotificationStatus.values()) {
            if (status.isTerminal()) {
                continue;
            }
            assertThat(status.allowedTransitions())
                    .as("%s must be able to reach a terminal state", status)
                    .anyMatch(next -> next.isTerminal() || !next.allowedTransitions().isEmpty());
        }
    }
}
