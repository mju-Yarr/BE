package com.hq.backend.onboarding.dto;

import jakarta.validation.constraints.NotBlank;

public record OnboardingProgressRequest(@NotBlank String currentStep) {}
