package com.bhavesh.notification.security;

import com.bhavesh.notification.common.exception.ErrorCode;
import com.bhavesh.notification.common.exception.ProblemDetailFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Produces the 403 body when an <em>authenticated</em> caller lacks the
 * required authority — an ordinary service account hitting an administrator
 * endpoint, for example.
 *
 * <p>The distinction from {@link RestAuthenticationEntryPoint} matters: 401
 * means "I do not know who you are", 403 means "I know who you are and the
 * answer is no". Returning 401 for the second case would send a correctly
 * authenticated client into a pointless re-authentication loop.
 *
 * <p>Note this handler is never reached for "this notification belongs to
 * another caller" — those lookups are scoped by {@code createdBy} in the query
 * and surface as 404 by design, so the API does not confirm that someone
 * else's id exists.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {

        String traceId = ProblemDetailFactory.newTraceId();
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        log.warn("403 ACCESS_DENIED [traceId={}] principal={} on {} {}",
                traceId, authentication == null ? "anonymous" : authentication.getPrincipal(),
                request.getMethod(), request.getRequestURI());

        response.setStatus(ErrorCode.ACCESS_DENIED.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Trace-Id", traceId);

        objectMapper.writeValue(response.getOutputStream(),
                ProblemDetailFactory.create(ErrorCode.ACCESS_DENIED,
                        ErrorCode.ACCESS_DENIED.defaultDetail(), request.getRequestURI(), traceId));
    }
}
