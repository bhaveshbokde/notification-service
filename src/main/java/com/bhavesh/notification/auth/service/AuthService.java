package com.bhavesh.notification.auth.service;

import com.bhavesh.notification.auth.dto.LoginRequest;
import com.bhavesh.notification.auth.dto.LoginResponse;
import com.bhavesh.notification.common.exception.ApiException;
import com.bhavesh.notification.common.exception.ErrorCode;
import com.bhavesh.notification.security.JwtService;
import com.bhavesh.notification.user.domain.User;
import com.bhavesh.notification.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Authenticates API consumers and issues tokens.
 *
 * <p>There is no registration endpoint. This is internal infrastructure: its
 * callers are other services, provisioned by an administrator, not members of
 * the public signing themselves up. An open registration endpoint on a service
 * that can send email and SMS to arbitrary recipients would be an open relay.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A real BCrypt hash of a throwaway value, used to equalise timing on an
     * unknown username. See {@link #login}.
     *
     * <p>Generated once at construction from random bytes, so it matches
     * nothing and is not a committed credential.
     */
    private final String dummyHash;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;

        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        this.dummyHash = passwordEncoder.encode(Base64.getEncoder().encodeToString(random));
    }

    /**
     * Exchanges credentials for a token.
     *
     * <p>Performs the password comparison <em>even when the username is
     * unknown</em>, against {@link #dummyHash}. Skipping it would make a miss
     * return in microseconds while a hit spends ~250ms in BCrypt, and that
     * difference is measurable over the network — a timing oracle that lets
     * someone enumerate which service accounts exist. Both paths now cost one
     * BCrypt verification and return the same {@code INVALID_CREDENTIALS}.
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Optional<User> maybeUser = userRepository.findByUsernameIgnoreCase(
                User.normalizeUsername(request.username()));

        String hashToCheck = maybeUser.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (maybeUser.isEmpty() || !passwordMatches) {
            // No username in the log line: a failed-login log should not be an
            // account list, and it is the first place someone looks after a
            // credential-stuffing run.
            log.warn("Failed login attempt");
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        User user = maybeUser.get();
        if (!user.isEnabled()) {
            // Only reachable once the password is already correct, so this does
            // not leak the existence of an account to someone who cannot log in
            // anyway.
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }

        log.info("Login succeeded for user id={} role={}", user.getId(), user.getRole());

        return LoginResponse.of(
                jwtService.generateToken(user.getId(), user.getUsername(), user.getRole()),
                jwtService.accessTokenTtl().toSeconds(),
                user.getUsername(),
                user.getRole());
    }
}
