package com.hq.backend.calendar.dto;

import jakarta.validation.constraints.NotNull;

public record CalendarSourcePatchRequest(@NotNull Boolean syncEnabled) {}
