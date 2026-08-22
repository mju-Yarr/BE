package com.hq.backend.wellness.dto;

import com.hq.backend.wellness.DailyWellnessSummary;
import java.time.LocalDate;
import java.util.UUID;

// API 명세 §12.4. DWL은 건강 점수가 아닌 부담 요약 지표다. 원천 데이터가 없으면
// dwlScore는 null, dwlBand는 unknown이며 low로 가장하지 않는다.
public record DailySummaryResponse(
        UUID summaryId,
        LocalDate summaryDate,
        int eventCount,
        int totalOutdoorMinutes,
        String outdoorSource,
        // 홈 wrap 카드의 "정시 도착" 칸. arrivalSampleCount가 0이면 도착 결과를
        // 하나도 모르는 것이므로, 클라이언트는 0회로 표시하지 말고 칸을 감춘다.
        int onTimeCount,
        int arrivalSampleCount,
        String dwlBand,
        Short dwlScore,
        String cardScenario,
        String message,
        boolean isViewed
) {

    public static DailySummaryResponse from(DailyWellnessSummary summary) {
        return new DailySummaryResponse(
                summary.getSummaryId(),
                summary.getSummaryDate(),
                summary.getEventCount(),
                summary.getTotalOutdoorMinutes(),
                summary.getOutdoorSource(),
                summary.getOnTimeCount(),
                summary.getArrivalSampleCount(),
                summary.getDwlBand(),
                summary.getDwlScore(),
                summary.getCardScenario(),
                summary.getCardMessageSnapshot(),
                summary.isViewed());
    }
}
