package com.bhavesh.notification.user.repository;

import com.bhavesh.notification.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Data access for {@link User}.
 *
 * <p>Both finders lower-case in the query so they match the functional index
 * {@code uq_users_username_lower}, rather than forcing a sequential scan around
 * it.
 *
 * <p>Parameters are bound by JPA, never concatenated, so a value such as
 * {@code "' OR 1=1 --"} is compared as a literal string and cannot alter the
 * statement.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    @Query("SELECT u FROM User u WHERE lower(u.username) = lower(:username)")
    Optional<User> findByUsernameIgnoreCase(@Param("username") String username);

    /**
     * Provisioning pre-check.
     *
     * <p>A race is still possible between this and the insert; the unique index
     * is the real guarantee, and the service catches the resulting violation
     * and reports the same 409. Checking without the constraint is a race;
     * relying on the constraint without the check makes the common path throw.
     */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE lower(u.username) = lower(:username)")
    boolean existsByUsernameIgnoreCase(@Param("username") String username);
}
