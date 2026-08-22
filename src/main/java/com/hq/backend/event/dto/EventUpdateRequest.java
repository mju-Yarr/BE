package com.hq.backend.event.dto;

import com.hq.backend.event.LocationState;
import java.time.Instant;

// 전부 nullable — 온 필드만 반영한다. locationState는 절대 원칙 5에 따라 캘린더 동기화보다
// 항상 우선하므로 사용자 PATCH는 무조건 반영한다.
public record EventUpdateRequest(
        Instant startsAt,
        Instant endsAt,
        LocationState locationState,
        String destinationName,
        String destinationAddress,
        Double destinationLat,
        Double destinationLng,
        String meetingUrl,
        String eventKind,
        String displayLabel,
        Boolean autoManageExcluded,
        String anchorMode
) {
    public EventUpdateRequest(Instant startsAt, Instant endsAt, LocationState locationState,
            String destinationName, Double destinationLat, Double destinationLng, String meetingUrl,
            String eventKind, String displayLabel, Boolean autoManageExcluded) {
        this(startsAt, endsAt, locationState, destinationName, null, destinationLat, destinationLng,
                meetingUrl, eventKind, displayLabel, autoManageExcluded, null);
    }
}
