package com.bhavesh.notification.common.web;

import com.bhavesh.notification.common.domain.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gives every request a correlation id, in the log and in the response.
 *
 * <p>Without one, diagnosing a report means guessing which of a thousand log
 * lines around that timestamp belong to the request. With one, the caller
 * quotes the {@code X-Trace-Id} from the failed response and the grep is exact.
 *
 * <p>This matters more here than in a synchronous API. A notification's life
 * spans an HTTP request, an outbox poll, a Kafka publish, a consume and a
 * provider call — four threads and at least two processes. The MDC also carries
 * {@code notificationId}, set by the consumer and the delivery processor, so
 * the asynchronous half of the pipeline is correlatable even though the trace
 * id does not survive the Kafka boundary.
 *
 * <h2>Ordering</h2>
 * {@code HIGHEST_PRECEDENCE} so the id exists before anything else can log —
 * including the security filter chain, whose rejections are often the lines
 * worth correlating.
 *
 * <h2>The finally block is not optional</h2>
 * The MDC is backed by a {@link ThreadLocal}, and servlet containers pool
 * threads. A value left behind reappears on the next request served by that
 * thread, so every subsequent log line carries a stranger's trace id — worse
 * than having none at all, because it looks authoritative.
 *
 * <p>An inbound {@code X-Trace-Id} is honoured so a trace can span services,
 * but it is length-capped and stripped of anything outside
 * {@code [A-Za-z0-9_-]}. An attacker-supplied value lands in log files; a
 * newline in it would let them forge entire log entries.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(HEADER));
        if (traceId == null) {
            traceId = Ids.traceId();
        }

        MDC.put(MDC_KEY, traceId);
        // Set before the chain runs: once the response is committed, headers
        // can no longer be added, and a streaming or early-committed response
        // would otherwise lose it.
        response.setHeader(HEADER, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Returns null for anything unusable, so the caller mints a fresh id. */
    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_-]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }
}
