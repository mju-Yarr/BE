package com.hq.backend.event.dto;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventStatus;
import com.hq.backend.event.LocationState;
import com.hq.backend.event.SourceType;
import com.hq.backend.plan.dto.PlanResponse;
import com.hq.backend.plan.dto.RouteOptionResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

public record EventResponse(
        UUID eventId,
        String displayName,
        Instant startsAt,
        Instant endsAt,
        String timezone,
        LocationState locationState,
        String destinationName,
        String destinationAddress,
        Double destinationLat,
        Double destinationLng,
        String meetingUrl,
        String eventKind,
        EventStatus status,
        boolean autoManageExcluded,
        SourceType sourceType,
        UUID calendarSourceId,
        String anchorMode,
        RouteOptionResponse route,
        PlanResponse plan
) {
    public static EventResponse from(Event event, String timezone) {
        return from(event, timezone, null, null);
    }

    public static EventResponse from(Event event, String timezone, PlanResponse plan) {
        return from(event, timezone, plan, null);
    }

    public static EventResponse from(Event event, String timezone, PlanResponse plan, RouteOptionResponse route) {
        return new EventResponse(event.getEventId(), resolveDisplayName(event, timezone), event.getStartsAt(),
                event.getEndsAt(), timezone, LocationState.valueOf(event.getLocationState().toUpperCase()),
                event.getDestinationName(), event.getDestinationAddress(), event.getDestinationLat(),
                event.getDestinationLng(), event.getMeetingUrl(), event.getEventKind(),
                EventStatus.valueOf(event.getStatus().toUpperCase()), event.isAutoManageExcluded(),
                SourceType.valueOf(event.getSourceType().toUpperCase()), event.getCalendarSourceId(),
                event.getAnchorMode(), route, plan);
    }

    private static String resolveDisplayName(Event event, String timezone) {
        if (event.getDisplayLabel() != null && !event.getDisplayLabel().isBlank()) return event.getDisplayLabel();
        if (event.getDestinationName() != null && !event.getDestinationName().isBlank()) return event.getDestinationName();
        ZonedDateTime local = event.getStartsAt().atZone(ZoneId.of(timezone));
        String period = local.getHour() < 12 ? "오전" : "오후";
        int hour12 = local.getHour() % 12 == 0 ? 12 : local.getHour() % 12;
        return period + " " + hour12 + "시 일정";
    }
}
