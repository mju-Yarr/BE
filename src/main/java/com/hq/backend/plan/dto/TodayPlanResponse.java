package com.hq.backend.plan.dto;

import com.hq.backend.event.dto.EventResponse;
import com.hq.backend.wellness.dto.DailySummaryResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record TodayPlanResponse(
        Instant serverNow,
        LocalDate date,
        String homeState,
        List<Card> cards,
        DailySummaryResponse wrapSummary,
        List<String> degraded) {
    public record Card(String state, EventResponse event, PlanDetailResponse plan) {}
}
