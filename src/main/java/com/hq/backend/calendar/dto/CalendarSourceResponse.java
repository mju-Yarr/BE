package com.hq.backend.calendar.dto;

import com.hq.backend.calendar.CalendarSource;
import java.util.UUID;

public record CalendarSourceResponse(
        UUID calendarSourceId,
        String displayName,
        boolean writable,
        boolean defaultSource,
        boolean syncEnabled) {
    public static CalendarSourceResponse from(CalendarSource source) {
        return new CalendarSourceResponse(source.getCalendarSourceId(), source.getDisplayName(),
                source.isWritable(), source.isDefault(), source.isSyncEnabled());
    }
}
