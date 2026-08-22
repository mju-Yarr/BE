package com.hq.backend.plan;

import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.plan.dto.TodayPlanResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/plans")
@RequiredArgsConstructor
public class TodayPlanController {
    private final TodayPlanService todayPlanService;

    @GetMapping("/today")
    public TodayPlanResponse today(@CurrentUserId UUID userId) {
        return todayPlanService.getToday(userId);
    }
}
