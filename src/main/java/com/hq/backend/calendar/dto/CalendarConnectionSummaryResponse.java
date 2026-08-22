package com.hq.backend.calendar.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CalendarConnectionSummaryResponse(
        UUID calendarConnectionId,
        String provider,
        String externalAccountId,
        Instant connectedAt,
        Instant lastSyncedAt,
        List<CalendarSourceResponse> sources) {}
