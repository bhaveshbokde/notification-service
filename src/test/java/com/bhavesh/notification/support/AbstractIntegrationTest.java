package com.bhavesh.notification.support;

import com.bhavesh.notification.common.web.TraceIdFilter;
import com.bhavesh.notification.user.domain.User;
import com.bhavesh.notification.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared base for every integration test.
 *
 * <h2>Why not {@code @Transactional} on the test class</h2>
 * The usual trick — annotate the test and let Spring roll back afterwards — is
 * wrong for this project. The behaviour under test <em>is</em> transactional:
 * the outbox depends on a notification and its event committing together, and
 * the idempotency claim depends on {@code REQUIRES_NEW} committing
 * independently. Wrapping the test in an outer transaction would make those
 * boundaries invisible, hide commit-time constraint violations, and make the
 * concurrency tests meaningless — a second thread cannot see an uncommitted
 * row.
 *
 * <p>So these tests commit for real and clean up explicitly in
 * {@link #resetDatabase}.
 *
 * <h2>MockMvc, not a real port</h2>
 * {@code MockMvc} exercises the full filter chain, the dispatcher, argument
 * resolution, validation, the exception handler and JSON serialisation —
 * without binding a socket. A random port would add a client, an HTTP
 * connection and a source of flakiness for nothing.
 *
 * <p>One consequence worth knowing: {@code springSecurity()} registers only the
 * security filter chain. Ordinary {@code Filter} beans are registered by Boot's
 * servlet container setup, which MockMvc does not run — so
 * {@link TraceIdFilter} has to be added by hand or {@code X-Trace-Id} is
 * missing in tests while working perfectly in production.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
public abstract class AbstractIntegrationTest {

    /** The password every test account is created with. */
    protected static final String TEST_PASSWORD = "integration-test-password";

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    @Autowired
    protected EntityManager entityManager;

    @Autowired
    protected TraceIdFilter traceIdFilter;

    protected MockMvc mockMvc;

    @BeforeEach
    void baseSetUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .addFilter(traceIdFilter, "/*")
                .build();
        resetDatabase();
    }

    /**
     * Returns the database to a known state between test methods.
     *
     * <p>Deletion order follows the foreign keys from child to parent —
     * removing {@code users} first would fail on
     * {@code notifications.created_by}, which is {@code ON DELETE RESTRICT}
     * precisely so a notification cannot be orphaned.
     */
    protected void resetDatabase() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM dead_letter_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM idempotency_records").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM notification_attempts").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM notifications").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users").executeUpdate();
        });
    }

    // ------------------------------------------------------------ test accounts --

    @Transactional
    protected User createUser(String username) {
        return userRepository.saveAndFlush(
                User.createUser(username, passwordEncoder.encode(TEST_PASSWORD)));
    }

    @Transactional
    protected User createAdmin(String username) {
        return userRepository.saveAndFlush(
                User.createAdmin(username, passwordEncoder.encode(TEST_PASSWORD)));
    }

    /** Logs in through the real endpoint, so the token is one the filter chain will accept. */
    protected String tokenFor(String username) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s"}
                                """.formatted(username, TEST_PASSWORD)))
                .andReturn().getResponse().getContentAsString();

        return "Bearer " + objectMapper.readTree(body).get("accessToken").asText();
    }

    protected String userToken(String username) throws Exception {
        createUser(username);
        return tokenFor(username);
    }

    protected String adminToken(String username) throws Exception {
        createAdmin(username);
        return tokenFor(username);
    }

    protected JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    // ------------------------------------------------------------ db assertions --

    protected long countRows(String table) {
        return transactionTemplate.execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM " + table)
                .getSingleResult()).longValue());
    }

    protected String notificationStatus(UUID id) {
        return transactionTemplate.execute(status -> (String) entityManager
                .createNativeQuery("SELECT status FROM notifications WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult());
    }

    protected String outboxStatusFor(UUID notificationId) {
        return transactionTemplate.execute(status -> {
            var results = entityManager
                    .createNativeQuery("SELECT status FROM outbox_events WHERE aggregate_id = :id")
                    .setParameter("id", notificationId)
                    .getResultList();
            return results.isEmpty() ? null : (String) results.get(0);
        });
    }

    protected long attemptCount(UUID notificationId) {
        return transactionTemplate.execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM notification_attempts WHERE notification_id = :id")
                .setParameter("id", notificationId)
                .getSingleResult()).longValue());
    }
}
