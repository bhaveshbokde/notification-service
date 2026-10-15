package com.bhavesh.notification.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credentials.
 *
 * <p>No {@code @Email} or format constraint on the username: a malformed value
 * at login is not a validation problem — it simply will not match any account —
 * and answering 400 "that is not a valid username" distinguishes it from 401
 * "wrong credentials", which is a small enumeration oracle. Both cases should
 * look identical from outside.
 */
@Schema(name = "LoginRequest", description = "API consumer credentials")
public record LoginRequest(

        @Schema(example = "admin")
        @NotBlank(message = "username is required")
        @Size(max = 64)
        String username,

        @Schema(format = "password")
        @NotBlank(message = "password is required")
        @Size(max = 72)
        String password
) {

    /** Never prints the password, so a logged DTO cannot leak a credential. */
    @Override
    public String toString() {
        return "LoginRequest{username='%s'}".formatted(username);
    }
}
