package com.hq.backend.route;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.backend.provider.Leg;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** route search에서 event 생성까지 보존되는 서버 측 후보 snapshot. */
public record SelectedRouteSearch(
        UUID routeOptionId,
        UUID originPlaceId,
        String originName,
        double originLat,
        double originLng,
        String anchorMode,
        Instant requestedAt,
        double destinationLat,
        double destinationLng,
        List<com.hq.backend.provider.RouteOption> routes,
        com.hq.backend.provider.RouteOption selectedRoute
) {

    static SelectedRouteSearch from(RouteSearchOption selected, List<RouteSearchOption> options) {
        List<com.hq.backend.provider.RouteOption> routes = options.stream()
                .map(SelectedRouteSearch::toProviderRoute)
                .toList();
        com.hq.backend.provider.RouteOption selectedRoute = routes.stream()
                .filter(route -> route.id().equals(selected.getRouteSearchOptionId().toString()))
                .findFirst()
                .orElseThrow();
        return new SelectedRouteSearch(
                selected.getRouteSearchOptionId(), selected.getOriginPlaceId(), selected.getOriginName(),
                selected.getOriginLat(), selected.getOriginLng(), selected.getAnchorMode(), selected.getRequestedAt(),
                selected.getDestinationLat(), selected.getDestinationLng(), routes, selectedRoute);
    }

    private static com.hq.backend.provider.RouteOption toProviderRoute(RouteSearchOption option) {
        return new com.hq.backend.provider.RouteOption(
                option.getRouteSearchOptionId().toString(), option.getRouteType(),
                option.getTotalSeconds(), option.getWalkSeconds(), option.getTransferCount(), option.getOutdoorSeconds(),
                parseLegs(option.getLegs()), option.getDepartAt(), option.getArriveAt(), option.getProvider(), option.getRawRef());
    }

    private static List<Leg> parseLegs(String json) {
        try {
            return new ObjectMapper().readValue(json, new TypeReference<List<Leg>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
