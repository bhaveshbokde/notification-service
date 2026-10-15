package com.bhavesh.notification.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * An API consumer.
 *
 * <p>Maps to {@code users} (V1). A notification platform is internal
 * infrastructure: its callers are other services and a handful of operators,
 * not the public. There is therefore no self-registration endpoint, and
 * accounts are provisioned by an administrator.
 *
 * <h2>Deliberate omissions</h2>
 * <ul>
 *   <li>No {@code @OneToMany} to notifications. One service account may submit
 *       millions; a mapped collection invites someone to call
 *       {@code user.getNotifications()} and load all of them. Navigation goes
 *       the other way, through repositories that always take a {@code userId}
 *       and a {@code Pageable}.</li>
 *   <li>No setter accepting a raw password. The only way in is
 *       {@link #setPasswordHash(String)}, called with an already-encoded value.
 *       This class never sees plain text and never encodes.</li>
 * </ul>
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /**
     * {@code EnumType.STRING}, never {@code ORDINAL}. An ordinal mapping stores
     * the enum's position, so inserting a constant in the middle of the enum
     * silently re-labels every existing row — here, that would turn ordinary
     * callers into administrators.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role = Role.ROLE_USER;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** JPA requires a no-arg constructor; it is not meant for application code. */
    protected User() {
    }

    private User(String username, String passwordHash, Role role) {
        this.username = normalizeUsername(username);
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Ordinary API consumer.
     *
     * <p>Hard-codes {@link Role#ROLE_USER}. There is no parameter through which
     * a caller could request a different role, which is what makes privilege
     * escalation structurally impossible rather than merely validated against.
     *
     * @param passwordHash an <em>already encoded</em> password
     */
    public static User createUser(String username, String passwordHash) {
        return new User(username, passwordHash, Role.ROLE_USER);
    }

    /**
     * Administrator.
     *
     * <p>Reachable only from
     * {@link com.bhavesh.notification.config.AdminBootstrap} and from an
     * administrator-only endpoint — never from an unauthenticated request.
     */
    public static User createAdmin(String username, String passwordHash) {
        return new User(username, passwordHash, Role.ROLE_ADMIN);
    }

    /** Canonical form used for both storage and lookup, matching {@code uq_users_username_lower}. */
    public static String normalizeUsername(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isAdmin() {
        return role == Role.ROLE_ADMIN;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
        this.updatedAt = Instant.now();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * Identity is the persistent id, and only once assigned.
     *
     * <p>Two transient instances are never equal, which keeps a
     * {@code HashSet} of unsaved entities from collapsing them into one.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    /**
     * A constant hash code is the standard recipe for a JPA entity with a
     * generated id: it stays stable across the transition from transient to
     * persistent, which {@code Objects.hash(id)} would not.
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(User.class);
    }

    /** Never includes {@code passwordHash}. */
    @Override
    public String toString() {
        return "User{id=%d, username='%s', role=%s, enabled=%s}"
                .formatted(id, username, role, enabled);
    }
}
