package com.bhavesh.notification.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-channel recipient rules and log masking. */
class NotificationChannelTest {

    @Nested
    @DisplayName("EMAIL recipients")
    class Email {

        @ParameterizedTest
        @ValueSource(strings = {
                "priya@example.com",
                "first.last@sub.domain.co.uk",
                "user+tag@example.org",
                "a@b.co",
        })
        void accepts(String recipient) {
            assertThat(NotificationChannel.EMAIL.isValidRecipient(recipient)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "not-an-email",
                "@example.com",
                "user@",
                "user@localhost",      // no TLD
                "two@at@example.com",
                "has space@example.com",
                "",
                "   ",
        })
        void rejects(String recipient) {
            assertThat(NotificationChannel.EMAIL.isValidRecipient(recipient)).isFalse();
        }

        @Test
        @DisplayName("requires a subject — an empty subject line is a deliverability problem")
        void requiresSubject() {
            assertThat(NotificationChannel.EMAIL.isSubjectRequired()).isTrue();
        }
    }

    @Nested
    @DisplayName("SMS recipients")
    class Sms {

        @ParameterizedTest
        @ValueSource(strings = {"+919876543210", "+14155552671", "+442071838750"})
        void acceptsE164(String recipient) {
            assertThat(NotificationChannel.SMS.isValidRecipient(recipient)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "9876543210",        // no country code — ambiguous across borders
                "+0123456789",       // country codes do not start with 0
                "+91 98765 43210",   // spaces
                "+91-98765-43210",   // hyphens
                "+1234",             // too short
                "+1234567890123456", // too long for E.164
                "not-a-number",
        })
        void rejectsNonE164(String recipient) {
            assertThat(NotificationChannel.SMS.isValidRecipient(recipient)).isFalse();
        }

        @Test
        void doesNotRequireSubject() {
            assertThat(NotificationChannel.SMS.isSubjectRequired()).isFalse();
        }
    }

    @Nested
    @DisplayName("IN_APP recipients")
    class InApp {

        @ParameterizedTest
        @ValueSource(strings = {"user-42", "7f3a91", "tenant:user:9", "a.b_c-d"})
        void acceptsIdentifiers(String recipient) {
            assertThat(NotificationChannel.IN_APP.isValidRecipient(recipient)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"has space", "", "   ", "user@domain"})
        void rejectsNonIdentifiers(String recipient) {
            assertThat(NotificationChannel.IN_APP.isValidRecipient(recipient)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(NotificationChannel.class)
    @DisplayName("every channel rejects null")
    void rejectsNull(NotificationChannel channel) {
        assertThat(channel.isValidRecipient(null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(NotificationChannel.class)
    @DisplayName("every channel describes what it expects, for the error message")
    void describesExpectedFormat(NotificationChannel channel) {
        assertThat(channel.recipientDescription()).isNotBlank();
    }

    /**
     * A recipient is personal data. Logging it in full puts an email address or
     * a phone number into every log aggregator, screenshot and support ticket.
     * Masking keeps the line useful for correlation without retaining the value.
     */
    @Nested
    @DisplayName("masking for logs")
    class Masking {

        @ParameterizedTest(name = "{1} -> {2}")
        @CsvSource({
                "EMAIL,  priya@example.com,    p***a@example.com",
                // A local part of two characters or fewer is replaced entirely,
                // star for star — there is no middle to hide, and revealing the
                // first and last would reveal the whole thing.
                "EMAIL,  a@b.co,               *@b.co",
                "EMAIL,  ab@b.co,              **@b.co",
                "SMS,    +919876543210,        +919****3210",
                "IN_APP, user-42,              u***2",
        })
        void masks(NotificationChannel channel, String recipient, String expected) {
            assertThat(channel.mask(recipient)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the email domain is kept — rarely personal, and the most useful part")
        void keepsEmailDomain() {
            assertThat(NotificationChannel.EMAIL.mask("someone@important-provider.com"))
                    .endsWith("@important-provider.com")
                    .doesNotContain("someone");
        }

        @Test
        @DisplayName("the phone country code and last four digits survive")
        void keepsPhoneCountryCodeAndTail() {
            String masked = NotificationChannel.SMS.mask("+919876543210");

            assertThat(masked).startsWith("+919").endsWith("3210");
            assertThat(masked)
                    .as("the middle digits must not be recoverable")
                    .doesNotContain("8765");
        }

        @ParameterizedTest
        @EnumSource(NotificationChannel.class)
        @DisplayName("a null or blank recipient masks to a placeholder rather than throwing")
        void handlesAbsentRecipient(NotificationChannel channel) {
            assertThat(channel.mask(null)).isEqualTo("(none)");
            assertThat(channel.mask("   ")).isEqualTo("(none)");
        }

        @ParameterizedTest
        @EnumSource(NotificationChannel.class)
        @DisplayName("a very short recipient is fully masked rather than partially revealed")
        void shortValuesAreFullyMasked(NotificationChannel channel) {
            assertThat(channel.mask("ab")).doesNotContain("a").doesNotContain("b");
        }

        @Test
        @DisplayName("two different recipients mask differently, so logs stay correlatable")
        void maskingPreservesDistinguishability() {
            assertThat(NotificationChannel.EMAIL.mask("alice@example.com"))
                    .isNotEqualTo(NotificationChannel.EMAIL.mask("bob@example.com"));
        }
    }
}
