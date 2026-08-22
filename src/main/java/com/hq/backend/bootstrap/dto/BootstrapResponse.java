package com.hq.backend.bootstrap.dto;

import com.hq.backend.onboarding.dto.OnboardingProgressResponse;
import com.hq.backend.permission.dto.PermissionResponse;
import com.hq.backend.plan.dto.TodayPlanResponse;
import com.hq.backend.preprule.dto.PrepRuleResponse;
import java.util.List;

public record BootstrapResponse(
        UserSummary user,
        SettingsSummary settings,
        PermissionsAndOnboarding gate,
        List<PermissionResponse> permissions,
        List<PlaceSummary> places,
        List<PrepRuleResponse> prepItems,
        TodayPlanResponse todayPlan,
        EngineConfigSummary engineConfig
) {
    public record PermissionsAndOnboarding(OnboardingProgressResponse onboarding) {}
}
