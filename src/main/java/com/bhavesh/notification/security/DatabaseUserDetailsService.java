package com.bhavesh.notification.security;

import com.bhavesh.notification.user.domain.User;
import com.bhavesh.notification.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Looks a caller up by username for Spring Security.
 *
 * <p>Exists for two reasons, and the second is the one worth recording.
 *
 * <p>The first is ordinary: anything in the framework that needs to resolve a
 * username goes through this type, and it should read the real user table.
 *
 * <p>The second is that <strong>without a {@code UserDetailsService} bean,
 * Spring Boot's {@code UserDetailsServiceAutoConfiguration} creates one for
 * you</strong>: a single in-memory user named {@code user}, with a random
 * password printed to the startup log. Harmless here — nothing in the
 * bearer-token path consults it — but it is a credential sitting in the
 * application context and in the logs for no reason, and the warning it prints
 * is exactly the kind of noise that trains people to ignore warnings.
 *
 * <p>Note that {@link JwtAuthenticationFilter} does <em>not</em> call this
 * class. Rebuilding the principal from verified token claims is what keeps the
 * filter chain stateless; routing every request through a database lookup would
 * undo that.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public DatabaseUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsernameIgnoreCase(User.normalizeUsername(username))
                .orElseThrow(() -> new UsernameNotFoundException("No account for that username"));
        return UserPrincipal.from(user);
    }
}
