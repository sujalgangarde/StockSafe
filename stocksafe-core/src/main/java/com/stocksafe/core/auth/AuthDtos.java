package com.stocksafe.core.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request and response shapes for /api/v1/auth. */
public final class AuthDtos {
    private AuthDtos() {}

    public record SignupRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Size(max = 120) String businessName) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds,
                                UUID tenantId, String role) {}
}
