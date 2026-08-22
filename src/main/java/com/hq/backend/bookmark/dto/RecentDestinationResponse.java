package com.hq.backend.bookmark.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RecentDestinationResponse(
        UUID recentDestinationId,
        String placeName,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        boolean bookmarked,
        Instant lastUsedAt) {}
