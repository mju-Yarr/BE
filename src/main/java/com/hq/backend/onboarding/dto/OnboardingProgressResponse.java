package com.hq.backend.onboarding.dto;

import com.hq.backend.onboarding.UserOnboarding;
import java.time.Instant;

public record OnboardingProgressResponse(
        String currentStep,
        boolean completed,
        Instant completedAt,
        boolean coachmarkSeen) {
    public static OnboardingProgressResponse from(UserOnboarding value) {
        return new OnboardingProgressResponse(value.getCurrentStep(), value.getCompletedAt() != null,
                value.getCompletedAt(), value.getCoachmarkSeenAt() != null);
    }
}
