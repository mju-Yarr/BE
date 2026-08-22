package com.hq.backend.bookmark.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record BookmarkPatchRequest(
        String placeName,
        String address,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal lng,
        String folder,
        @PositiveOrZero Integer sortOrder) {}
