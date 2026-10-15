package com.bhavesh.notification.config;

import com.bhavesh.notification.security.JwtAuthenticationFilter;
import com.bhavesh.notification.security.RestAccessDeniedHandler;
import com.bhavesh.notification.security.RestAuthenticationEntryPoint;
import com.bhavesh.notification.user.domain.Role;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The security filter chain.
 *
 * <p>Authorization is expressed here as URL rules rather than scattered across
 * controllers, so the whole policy can be read in one place. Method-level
 * {@code @PreAuthorize} is enabled too, for the cases where the rule depends on
 * something a URL cannot express — and as a second, independent check on the
 * administrative endpoints.
 *
 * <h2>This API has almost no public surface</h2>
 * Only login, the health probes and the API documentation are open. Unlike a
 * consumer-facing service there is no public catalogue to browse: everything
 * this service does is on behalf of an authenticated caller, and a notification
 * endpoint that accepted anonymous requests would be an open relay for email
 * and SMS.
 */
@Configuration
@EnableMethodSecurity   // enables @PreAuthorize / @PostAuthorize
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final CorsProperties corsProperties;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler,
                          CorsProperties corsProperties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.corsProperties = corsProperties;
    }

    /**
     * BCrypt at strength 12.
     *
     * <p>BCrypt is deliberately slow and salts automatically — a per-password
     * salt is embedded in the output, so identical passwords produce different
     * hashes and one rainbow table cannot cover the whole table. SHA-256 would
     * be wrong here precisely because it is fast: an attacker with a leaked
     * table could try billions of candidates per second.
     *
     * <p>Strength 12 means 2^12 rounds, roughly 250ms on current hardware
     * against ~60ms for the default 10. Paid once per login, and it makes
     * offline cracking four times more expensive.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CSRF protects cookie-authenticated browser sessions. This API
            // authenticates with a bearer token the client must attach
            // explicitly, so a cross-site form post carries no credentials and
            // there is nothing for a token to defend. Disabled deliberately,
            // not by neglect.
            .csrf(AbstractHttpConfigurer::disable)

            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // No session, no JSESSIONID, no server-side state to replicate.
            // Every request carries its own proof of identity.
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Both emit the same RFC 9457 body as every other error. Without
            // them, Spring Security's defaults would return a differently
            // shaped 401/403.
            .exceptionHandling(handling -> handling
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))

            .authorizeHttpRequests(auth -> auth
                    // --- public ---
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**",
                                     "/actuator/info").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                     "/swagger-ui.html").permitAll()
                    // HEAD as well as GET: a matcher for GET does not match
                    // HEAD, so an uptime monitor probing a health endpoint
                    // would otherwise get a 401.
                    .requestMatchers(HttpMethod.HEAD, "/actuator/health/**").permitAll()

                    // --- administrator ---
                    // Listed before the catch-all below: the first matching
                    // rule wins, so a broader rule placed earlier would shadow
                    // these.
                    .requestMatchers("/api/v1/admin/**").hasRole(Role.ROLE_ADMIN.shortName())
                    // Metrics and Prometheus expose internal detail about
                    // throughput and failure rates. Not public.
                    .requestMatchers("/actuator/**").hasRole(Role.ROLE_ADMIN.shortName())

                    // --- everything else needs a valid token ---
                    .anyRequest().authenticated())

            // Runs before the username/password filter so a request arrives at
            // the authorization rules already authenticated. Placed after, the
            // rules would evaluate an empty context and reject every call.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)

            .headers(headers -> headers
                    .frameOptions(frame -> frame.sameOrigin())
                    .contentTypeOptions(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * CORS, with origins from configuration rather than a wildcard.
     *
     * <p>{@code allowCredentials(false)} is correct here and worth stating:
     * this API authenticates with an {@code Authorization} header, not a
     * cookie, so the browser never needs to attach ambient credentials. Setting
     * it true would also forbid a wildcard origin — which this does not use
     * anyway — and would widen the attack surface for no benefit.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type",
                "Idempotency-Key", "Accept"));
        configuration.setExposedHeaders(List.of("X-Trace-Id",
                "X-RateLimit-Limit", "X-RateLimit-Remaining", "Retry-After"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
