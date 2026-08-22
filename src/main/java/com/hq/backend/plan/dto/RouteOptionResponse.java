package com.hq.backend.plan.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.backend.plan.RouteOption;
import com.hq.backend.provider.Leg;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RouteOptionResponse(
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

    public static RouteOptionResponse from(RouteOption route) {
        return new RouteOptionResponse(route.getRouteOptionId(), route.getRouteRank(), route.getRouteType(),
                route.getTotalMinutes(), route.getWalkMinutes(), route.getTransferCount(), route.getDepartAt(),
                route.getArriveAt(), route.getProvider(), parseLegs(route.getLegs()), parseStrings(route.getDegraded()));
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
