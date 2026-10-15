package com.bhavesh.notification.security;

import com.bhavesh.notification.user.domain.Role;
import com.bhavesh.notification.user.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated caller, as the rest of the application sees it.
 *
 * <p>This is the object behind {@code SecurityContextHolder …getPrincipal()}
 * and behind every {@code @AuthenticationPrincipal UserPrincipal} controller
 * parameter.
 *
 * <p>It carries the database {@code id}, which is the detail that makes
 * per-caller data isolation cheap: a controller passes {@code principal.getId()}
 * straight into a repository method such as {@code findByIdAndCreatedBy(...)},
 * so ownership becomes part of the SQL predicate rather than an afterthought
 * check on an already-loaded row.
 *
 * <p>A distinct type from {@link User} on purpose — an entity is a persistence
 * concern and should not be attached to the security context, where a stale or
 * lazily-initialised instance could outlive its persistence context.
 */
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String username;
    private final String passwordHash;
    private final Role role;
    private final boolean enabled;

    public UserPrincipal(Long id, String username, String passwordHash, Role role, boolean enabled) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
    }

    public static UserPrincipal from(User user) {
        return new UserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(),
                user.getRole(), user.isEnabled());
    }

    /**
     * Rebuilds a principal from verified JWT claims, without a database round
     * trip.
     *
     * <p>This is what makes the filter chain stateless. The trade-off is
     * explicit: a role change or an account disablement does not take effect
     * until the current token expires (60 minutes by default). Checking the
     * database on every request would close the window and would also be
     * exactly the round trip the JWT exists to avoid. Documented in
     * docs/ARCHITECTURE.md.
     */
    public static UserPrincipal fromClaims(Long id, String username, Role role) {
        return new UserPrincipal(id, username, null, role, true);
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public boolean isAdmin() {
        return role == Role.ROLE_ADMIN;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    /** Null for a principal reconstructed from a token — no hash is ever put in a JWT. */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** Never includes the password hash. */
    @Override
    public String toString() {
        return "UserPrincipal{id=%d, username='%s', role=%s}".formatted(id, username, role);
    }
}
