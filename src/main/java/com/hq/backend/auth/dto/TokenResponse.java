package com.hq.backend.auth.dto;

import com.hq.backend.onboarding.dto.OnboardingProgressResponse;
import java.util.List;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserInfo user,
        List<String> consentRequired,
        OnboardingProgressResponse onboarding
) {
    public record UserInfo(String userId, String nickname, String timezone, boolean isNew) {}
}
