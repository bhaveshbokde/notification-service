package com.bhavesh.notification.security;

import com.bhavesh.notification.common.exception.ApiException;
import com.bhavesh.notification.common.exception.ErrorCode;
import com.bhavesh.notification.common.exception.ProblemDetailFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Produces the 401 body for unauthenticated requests to protected endpoints.
 *
 * <p>Exists because the global exception handler cannot help here: the security
 * filter chain runs <em>before</em> the {@code DispatcherServlet}, so nothing a
 * filter throws ever reaches a {@code @RestControllerAdvice}. Without this
 * class the caller would get Spring Security's default empty 401 — a different
 * shape from every other error this API returns.
 *
 * <p>It reads the attribute set by {@link JwtAuthenticationFilter} so the
 * response distinguishes an <em>expired</em> token (re-authenticate) from an
 * <em>invalid</em> one (the token is wrong, not stale), which a bare 401
 * cannot.
 *
 * <p>The {@code WWW-Authenticate} header follows RFC 6750 §3.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ErrorCode code = ErrorCode.AUTHENTICATION_REQUIRED;
        String detail = "Authentication is required to access this resource";

        if (request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR) instanceof ApiException tokenError) {
            code = tokenError.errorCode();
            detail = tokenError.getMessage();
        }

        String traceId = ProblemDetailFactory.newTraceId();
        log.warn("401 {} [traceId={}] on {} {}", code, traceId, request.getMethod(), request.getRequestURI());

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Trace-Id", traceId);
        response.setHeader("WWW-Authenticate",
                "Bearer realm=\"notification-service\", error=\"%s\"".formatted(code.name().toLowerCase()));

        objectMapper.writeValue(response.getOutputStream(),
                ProblemDetailFactory.create(code, detail, request.getRequestURI(), traceId));
    }
}
