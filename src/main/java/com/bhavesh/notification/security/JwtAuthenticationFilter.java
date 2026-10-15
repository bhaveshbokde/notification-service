package com.bhavesh.notification.security;

import com.bhavesh.notification.common.exception.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns a valid {@code Authorization: Bearer <jwt>} header into an
 * authenticated {@code SecurityContext} for the duration of one request.
 *
 * <p>Extends {@link OncePerRequestFilter} so the work happens exactly once even
 * when the container performs a {@code FORWARD} or {@code ERROR} dispatch — a
 * plain {@code Filter} can run several times per request and would re-parse the
 * token each time.
 *
 * <h2>Failure behaviour</h2>
 * A bad or expired token does <strong>not</strong> abort the chain here. The
 * filter records the reason, leaves the context unauthenticated and continues.
 * The authorization rules then decide: a public endpoint still succeeds, a
 * protected one is rejected by {@link RestAuthenticationEntryPoint}, which
 * reads the attribute set below to return the precise code
 * ({@code TOKEN_EXPIRED} vs {@code TOKEN_INVALID}). Keeping rejection in one
 * place is what makes every 401 body identical.
 *
 * <h2>The Authorization header is never logged</h2>
 * Not at DEBUG, not truncated, not hashed. A bearer token in a log file is a
 * live credential in a log aggregator, and "we only log the first 8 characters"
 * is the kind of mitigation that stops being true during an incident.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute carrying the rejection reason to the entry point. */
    public static final String ATTR_AUTH_ERROR = "notification.auth.error";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MDC_USER_ID = "userId";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String token = extractBearerToken(request);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UserPrincipal principal = jwtService.verify(token);
                authenticate(principal, request);
                MDC.put(MDC_USER_ID, String.valueOf(principal.getId()));
            } catch (ApiException ex) {
                // Stash the reason; never write a response from inside the filter.
                request.setAttribute(ATTR_AUTH_ERROR, ex);
                SecurityContextHolder.clearContext();
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // The MDC is a ThreadLocal and the container pools threads. A value
            // left behind would mislabel the next request served by this thread.
            MDC.remove(MDC_USER_ID);
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(UserPrincipal principal, HttpServletRequest request) {
        // Credentials are null: the bearer token has already been verified, and
        // holding it in the context would put it in any heap dump.
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /** Returns the raw token, or null when the header is absent or not a Bearer header. */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
