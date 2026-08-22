package com.hq.backend.event.dto;

import com.hq.backend.event.LocationState;
import com.hq.backend.event.SourceType;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

// originPlaceId/selectedRouteOptionId/anchorMode/writeToCalendarSourceId는 계획 생성·경로
// 확정·캘린더 기록 시점에만 쓰이는 API 전용 필드다(§8.1). selectedRouteOptionId는 CAL-05의
// 사용자 소유 30분 TTL 후보를 가리키며 EventService가 이를 소비해 plan ROUTE_OPTION으로 확정한다.
public record EventCreateRequest(
        @NotNull Instant startsAt,
        Instant endsAt,
        @NotNull LocationState locationState,
        String destinationName,
        String destinationAddress,
        Double destinationLat,
        Double destinationLng,
        String meetingUrl,
        String eventKind,
        @NotNull SourceType sourceType,
        String anchorMode,
        UUID originPlaceId,
        UUID selectedRouteOptionId,
        String displayLabel,
        UUID writeToCalendarSourceId
) {
}
