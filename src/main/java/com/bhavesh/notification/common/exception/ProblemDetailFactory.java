package com.bhavesh.notification.common.exception;

import com.bhavesh.notification.common.domain.Ids;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Builds every error body this application returns, in RFC 9457 Problem
 * Details form.
 *
 * <p>Spring Framework 6 ships {@link ProblemDetail}, so the standard fields
 * ({@code type}, {@code title}, {@code status}, {@code detail},
 * {@code instance}) come for free and serialise with the correct
 * {@code application/problem+json} content type. RFC 9457 permits extension
 * members, which is where {@code code}, {@code traceId}, {@code timestamp} and
 * {@code errors} live.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "type":      "https://api.notification.local/problems/rate-limit-exceeded",
 *   "title":     "Rate Limit Exceeded",
 *   "status":    429,
 *   "detail":    "Too many requests; retry after 42 seconds",
 *   "instance":  "/api/v1/notifications",
 *   "code":      "RATE_LIMIT_EXCEEDED",
 *   "traceId":   "0f6b1c2d9a3e4f58",
 *   "timestamp": "2026-10-15T09:31:22.481Z"
 * }
 * }</pre>
 *
 * <p>Clients branch on {@code code}, which is stable; {@code detail} may be
 * reworded freely. {@code traceId} is echoed so a reported failure can be
 * correlated with a server log line without exposing a stack trace.
 */
public final class ProblemDetailFactory {

    /**
     * Base URI for the {@code type} member. It need not resolve — RFC 9457
     * says a type URI is an identifier first and a dereferenceable document
     * second — but using a stable namespace means a future documentation site
     * can be published at these URLs without changing any response.
     */
    private static final String TYPE_BASE = "https://api.notification.local/problems/";

    private ProblemDetailFactory() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    public static ProblemDetail create(ErrorCode code, String detail, String path, String traceId) {
        return create(code, detail, path, traceId, null);
    }

    /**
     * @param errors field-level violations; omitted from the body when null or empty
     */
    public static ProblemDetail create(ErrorCode code, String detail, String path, String traceId,
                                       List<FieldViolation> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(TYPE_BASE + slug(code)));
        problem.setTitle(title(code));
        if (path != null) {
            problem.setInstance(URI.create(path));
        }
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", traceId);
        problem.setProperty("timestamp", Instant.now().toString());
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    /** A short correlation id, also returned in the {@code X-Trace-Id} header. */
    public static String newTraceId() {
        return Ids.traceId();
    }

    /** {@code RATE_LIMIT_EXCEEDED} becomes {@code rate-limit-exceeded}. */
    private static String slug(ErrorCode code) {
        return code.name().toLowerCase().replace('_', '-');
    }

    /** {@code RATE_LIMIT_EXCEEDED} becomes {@code Rate Limit Exceeded}. */
    private static String title(ErrorCode code) {
        String[] words = code.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder(code.name().length());
        for (String word : words) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return sb.toString();
    }

    /**
     * One rejected field.
     *
     * <p>{@code rejectedValue} is rendered as a string and is redacted for any
     * field whose name suggests a secret or carries personal data. Validation
     * error bodies end up in logs, bug reports and screenshots — a rejected
     * password echoed into one of those is a credential in a log aggregator,
     * and a rejected {@code recipient} is someone's phone number.
     */
    public record FieldViolation(String field, String rejectedValue, String message) {

        public static FieldViolation of(String field, Object rejectedValue, String message) {
            return new FieldViolation(field, redact(field, rejectedValue), message);
        }

        private static String redact(String field, Object value) {
            if (field != null) {
                String lower = field.toLowerCase();
                if (lower.contains("password") || lower.contains("secret") || lower.contains("token")) {
                    return "***";
                }
                // Recipients and message bodies are personal data, so the value
                // is withheld even though knowing it would be convenient. The
                // message still says what was wrong with it.
                if (lower.contains("recipient") || lower.equals("body") || lower.contains("subject")) {
                    return "(redacted)";
                }
            }
            if (value == null) {
                return null;
            }
            String text = value.toString();
            return text.length() > 120 ? text.substring(0, 117) + "..." : text;
        }
    }
}
