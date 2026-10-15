package com.bhavesh.notification.auth.controller;

import com.bhavesh.notification.auth.dto.LoginRequest;
import com.bhavesh.notification.auth.dto.LoginResponse;
import com.bhavesh.notification.auth.service.AuthService;
import com.bhavesh.notification.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Authentication.
 *
 * <p>There is deliberately no registration endpoint — see
 * {@link com.bhavesh.notification.auth.service.AuthService}. Accounts are
 * provisioned by an administrator, because a service that can send email and
 * SMS to arbitrary recipients must not accept self-signup.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Token issuance for API consumers")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @SecurityRequirements   // public: overrides the global bearer requirement
    @Operation(summary = "Exchange credentials for a token",
            description = "Returns the same 401 for an unknown username and a wrong password, "
                    + "and takes the same time, so the endpoint cannot be used to enumerate "
                    + "accounts.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token issued"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials", content = @Content)
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    @Operation(summary = "The authenticated caller",
            description = "Echoes the identity carried by the presented token. Useful for "
                    + "confirming which account a token belongs to without decoding it.")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(Map.of(
                "id", principal.getId(),
                "username", principal.getUsername(),
                "role", principal.getRole(),
                "admin", principal.isAdmin()));
    }
}
