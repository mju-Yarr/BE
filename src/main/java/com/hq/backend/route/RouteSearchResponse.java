package com.hq.backend.route;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.backend.provider.Leg;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RouteSearchResponse(
        UUID routeOptionId,
        int routeRank,
        String routeType,
        int totalMinutes,
        int walkMinutes,
        int transferCount,
        Instant departAt,
        Instant arriveAt,
        String provider,
        List<Leg> legs,
        List<String> degraded
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static RouteSearchResponse from(RouteSearchOption option) {
        return new RouteSearchResponse(option.getRouteSearchOptionId(), option.getRouteRank(), option.getRouteType(),
                option.getTotalSeconds() / 60, option.getWalkSeconds() / 60, option.getTransferCount(),
                option.getDepartAt(), option.getArriveAt(), option.getProvider(), parseLegs(option.getLegs()),
                parseStrings(option.getDegraded()));
    }

    private static List<Leg> parseLegs(String json) {
        try { return MAPPER.readValue(json, new TypeReference<List<Leg>>() {}); }
        catch (Exception ignored) { return List.of(); }
    }

    private static List<String> parseStrings(String json) {
        try { return MAPPER.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception ignored) { return List.of(); }
    }
}
