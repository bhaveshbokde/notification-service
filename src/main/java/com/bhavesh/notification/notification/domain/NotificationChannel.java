package com.bhavesh.notification.notification.domain;

import java.util.regex.Pattern;

/**
 * The delivery channels this service supports.
 *
 * <p>Each channel owns its own recipient format, because "is this a valid
 * recipient?" is a different question for an email address than for a phone
 * number, and answering it in a shared validator would mean a chain of
 * {@code if (channel == EMAIL)} branches that drift apart over time.
 *
 * <p>The patterns here are deliberately permissive rather than exhaustive. A
 * fully RFC 5322-compliant email regex is famously several thousand characters
 * long and still cannot tell you whether the mailbox exists; the only real
 * validation is attempting delivery. The purpose of these checks is to reject
 * obvious nonsense before it costs a provider call, not to guarantee
 * deliverability.
 */
public enum NotificationChannel {

    /**
     * Email. Requires a subject; a message with an empty subject line is a
     * deliverability problem rather than a technical one, but it is one worth
     * catching at the API boundary.
     */
    EMAIL(
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$"),
            true,
            "a valid email address"),

    /**
     * SMS. Expects E.164: a leading {@code +}, a country code, and up to 15
     * digits total. Anything else is ambiguous once it crosses a border, and
     * most gateways reject it outright.
     */
    SMS(
            Pattern.compile("^\\+[1-9]\\d{7,14}$"),
            false,
            "an E.164 phone number such as +919876543210"),

    /**
     * In-app. The recipient is an internal user identifier, so the only rule
     * is that it is non-empty and plausibly an identifier. No subject: an
     * in-app message is a body and a timestamp.
     */
    IN_APP(
            Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$"),
            false,
            "an internal user identifier");

    private final Pattern recipientPattern;
    private final boolean subjectRequired;
    private final String recipientDescription;

    NotificationChannel(Pattern recipientPattern, boolean subjectRequired, String recipientDescription) {
        this.recipientPattern = recipientPattern;
        this.subjectRequired = subjectRequired;
        this.recipientDescription = recipientDescription;
    }

    public boolean isValidRecipient(String recipient) {
        return recipient != null
                && !recipient.isBlank()
                && recipientPattern.matcher(recipient.trim()).matches();
    }

    public boolean isSubjectRequired() {
        return subjectRequired;
    }

    /** Used in the validation error, so the caller is told what was expected. */
    public String recipientDescription() {
        return recipientDescription;
    }

    /**
     * How much of a recipient may appear in a log line.
     *
     * <p>A recipient is personal data. Logging it in full means every log
     * aggregator, every screenshot and every support ticket now contains an
     * email address or a phone number. Masking keeps the line useful for
     * correlation — you can still tell two recipients apart — without
     * retaining the value.
     *
     * <pre>
     *   priya@example.com  ->  p***a@example.com
     *   +919876543210      ->  +9198****3210
     *   user-42            ->  u***2
     * </pre>
     */
    public String mask(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return "(none)";
        }
        String value = recipient.trim();

        if (this == EMAIL) {
            int at = value.indexOf('@');
            if (at <= 0) {
                return maskMiddle(value);
            }
            // The domain is retained: it is rarely personal on its own and is
            // the most useful part when diagnosing a delivery problem.
            return maskMiddle(value.substring(0, at)) + value.substring(at);
        }

        if (this == SMS) {
            if (value.length() <= 8) {
                return maskMiddle(value);
            }
            // Keep the country code and the last four digits.
            return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
        }

        return maskMiddle(value);
    }

    private static String maskMiddle(String value) {
        if (value.length() <= 2) {
            return "*".repeat(value.length());
        }
        return value.charAt(0) + "***" + value.charAt(value.length() - 1);
    }
}
