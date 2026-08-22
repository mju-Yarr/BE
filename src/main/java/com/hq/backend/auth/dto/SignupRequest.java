package com.hq.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

/**
 * The extended fields implement the verified atomic signup flow. The two-argument constructor
 * keeps the legacy email-link signup contract source compatible.
 */
public record SignupRequest(
        String name,
        String nickname,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.") String password,
        String timezone,
        UUID installationId,
        String verificationTicket,
        Map<String, Boolean> consents
) {
    public SignupRequest(String email, String password) {
        this(null, null, email, password, null, null, null, null);
    }
}
