package com.bhavesh.notification.auth.dto;

import com.bhavesh.notification.user.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An issued access token.
 *
 * <p>{@code expiresInSeconds} is included so a client can re-authenticate
 * before expiry rather than waiting for a 401 and retrying. The value comes
 * from configuration, not from a constant in the client.
 *
 * <p>There is no refresh token. The callers here are services that hold their
 * own credentials and can re-authenticate whenever they like, so a refresh
 * token would be a second credential to store and revoke for no benefit.
 */
@Schema(name = "LoginResponse", description = "An issued access token")
public record LoginResponse(

        @Schema(description = "JWT for the Authorization header")
        String accessToken,

        @Schema(example = "Bearer")
        String tokenType,

        @Schema(example = "3600", description = "Token lifetime in seconds")
        long expiresInSeconds,

        @Schema(example = "admin")
        String username,

        @Schema(example = "ROLE_ADMIN")
        Role role
) {

    public static LoginResponse of(String accessToken, long expiresInSeconds,
                                   String username, Role role) {
        return new LoginResponse(accessToken, "Bearer", expiresInSeconds, username, role);
    }

    /** Never prints the token. */
    @Override
    public String toString() {
        return "LoginResponse{tokenType='%s', expiresInSeconds=%d, username='%s', role=%s}"
                .formatted(tokenType, expiresInSeconds, username, role);
    }
}
