package com.hq.backend.onboarding;

import com.hq.backend.common.exception.ApiException;
import com.hq.backend.onboarding.dto.OnboardingProgressResponse;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingService {
    public static final String FIRST_STEP = "profile";
    private static final Set<String> STEPS = Set.of(
            "profile", "prep", "places", "calendar", "first_event", "permissions", "wellness", "completed");

    private final UserOnboardingRepository repository;

    @Transactional(readOnly = true)
    public OnboardingProgressResponse get(UUID userId) {
        return OnboardingProgressResponse.from(find(userId));
    }

    @Transactional
    public OnboardingProgressResponse update(UUID userId, String step) {
        if (!STEPS.contains(step) || "completed".equals(step)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ONBOARDING_STEP",
                    "지원하지 않는 온보딩 단계입니다.");
        }
        UserOnboarding onboarding = find(userId);
        if (onboarding.getCompletedAt() != null) {
            return OnboardingProgressResponse.from(onboarding);
        }
        onboarding.setCurrentStep(step);
        onboarding.setUpdatedAt(Instant.now());
        return OnboardingProgressResponse.from(onboarding);
    }

    @Transactional
    public OnboardingProgressResponse complete(UUID userId) {
        UserOnboarding onboarding = find(userId);
        Instant now = Instant.now();
        if (onboarding.getCompletedAt() == null) onboarding.setCompletedAt(now);
        onboarding.setCurrentStep("completed");
        onboarding.setUpdatedAt(now);
        return OnboardingProgressResponse.from(onboarding);
    }

    @Transactional
    public void markCoachmarkSeen(UUID userId) {
        UserOnboarding onboarding = find(userId);
        if (onboarding.getCompletedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ONBOARDING_NOT_COMPLETED", "온보딩 완료 후 표시할 수 있습니다.");
        }
        if (onboarding.getCoachmarkSeenAt() == null) onboarding.setCoachmarkSeenAt(Instant.now());
        onboarding.setUpdatedAt(Instant.now());
    }

    public static UserOnboarding initial(UUID userId, Instant now) {
        return UserOnboarding.builder().userId(userId).currentStep(FIRST_STEP).updatedAt(now).build();
    }

    private UserOnboarding find(UUID userId) {
        return repository.findById(userId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "ONBOARDING_NOT_FOUND", "온보딩 상태를 찾을 수 없습니다."));
    }
}
