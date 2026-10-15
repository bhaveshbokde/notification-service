package com.bhavesh.notification.user.domain;

/**
 * Application roles.
 *
 * <p>The {@code ROLE_} prefix is kept in the enum name because Spring
 * Security's {@code hasRole("ADMIN")} expression prepends {@code ROLE_} before
 * comparing against the granted authority. Storing the prefixed form keeps the
 * database value, the {@code GrantedAuthority} and the SpEL expression
 * identical, with no translation step that could silently drift.
 */
public enum Role {

    /**
     * An ordinary API consumer — typically another service.
     *
     * <p>May submit notifications and read <em>its own</em>. It cannot see
     * notifications submitted by other callers, which matters because a
     * notification body routinely contains a one-time code or a password-reset
     * link belonging to someone else's users.
     */
    ROLE_USER,

    /**
     * Operator.
     *
     * <p>May read every notification, inspect the dead-letter queue and replay
     * failed events. That breadth is the reason administrative reads are
     * deliberately separated onto {@code /api/v1/admin/**} rather than being
     * extra query parameters on the ordinary endpoints — it keeps the
     * authorization rule structural rather than conditional.
     */
    ROLE_ADMIN;

    /** The authority string Spring Security compares against. */
    public String authority() {
        return name();
    }

    /** The bare name used by {@code hasRole(...)} expressions. */
    public String shortName() {
        return name().substring("ROLE_".length());
    }
}
