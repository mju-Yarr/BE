package com.hq.backend.onboarding;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.onboarding.dto.OnboardingProgressRequest;
import com.hq.backend.onboarding.dto.OnboardingProgressResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me/onboarding")
@RequiredArgsConstructor
public class OnboardingController {
    private final OnboardingService onboardingService;

    @GetMapping
    public OnboardingProgressResponse get(@CurrentUserId UUID userId) {
        return onboardingService.get(userId);
    }

    @PatchMapping
    public OnboardingProgressResponse update(@CurrentUserId UUID userId,
            @Valid @RequestBody OnboardingProgressRequest request) {
        return onboardingService.update(userId, request.currentStep());
    }

    @PostMapping("/complete")
    public OnboardingProgressResponse complete(@CurrentUserId UUID userId) {
        return onboardingService.complete(userId);
    }

    @PostMapping("/coachmark-seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void coachmarkSeen(@CurrentUserId UUID userId) {
        onboardingService.markCoachmarkSeen(userId);
    }
}
