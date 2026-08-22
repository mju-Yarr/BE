package com.hq.backend.route;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.place.PlaceCoordinateCodec;
import com.hq.backend.place.UserPlace;
import com.hq.backend.place.UserPlaceRepository;
import com.hq.backend.provider.GeoPoint;
import com.hq.backend.provider.RouteProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RouteSearchService {

    public static final Duration OPTION_TTL = Duration.ofMinutes(30);
    private static final String DEFAULT_ANCHOR_MODE = "arrive_by";

    private final RouteSearchOptionRepository routeSearchOptionRepository;
    private final UserPlaceRepository userPlaceRepository;
    private final PlaceCoordinateCodec placeCoordinateCodec;
    private final RouteProvider routeProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public List<RouteSearchResponse> search(UUID userId, RouteSearchRequest request) {
        Origin origin = resolveAndValidateOrigin(userId, request);
        validateCoordinatePair("destLat", request.destLat(), "destLng", request.destLng(), true);
        validateCoordinateBounds("destLat", request.destLat(), -90, 90);
        validateCoordinateBounds("destLng", request.destLng(), -180, 180);
        if (request.at() == null) {
            throw validationError("at은 필수입니다.");
        }
        String anchorMode = normalizeAnchorMode(request.anchorMode());
        Instant now = Instant.now();
        List<com.hq.backend.provider.RouteOption> routes = routeProvider.search(
                new GeoPoint(origin.lat(), origin.lng()),
                new GeoPoint(request.destLat(), request.destLng()),
                anchorMode,
                request.at());
        if (routes.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_SEARCH_UNAVAILABLE", "경로 후보를 찾을 수 없습니다.");
        }

        UUID searchSessionId = UUID.randomUUID();
        Instant expiresAt = now.plus(OPTION_TTL);
        List<RouteSearchOption> saved = new java.util.ArrayList<>();
        int rank = 1;
        for (com.hq.backend.provider.RouteOption route : routes) {
            saved.add(routeSearchOptionRepository.save(RouteSearchOption.builder()
                    .routeSearchOptionId(UUID.randomUUID())
                    .searchSessionId(searchSessionId)
                    .userId(userId)
                    .originPlaceId(origin.placeId())
                    .originName(origin.name())
                    .originLat(origin.lat())
                    .originLng(origin.lng())
                    .destinationLat(request.destLat())
                    .destinationLng(request.destLng())
                    .destinationName(request.destName())
                    .anchorMode(anchorMode)
                    .requestedAt(request.at())
                    .createdAt(now)
                    .routeRank(rank++)
                    .routeType(route.rank())
                    .totalSeconds(route.totalSec())
                    .walkSeconds(route.walkSec())
                    .transferCount(route.transfers())
                    .outdoorSeconds(route.outdoorSec())
                    .departAt(route.departAt())
                    .arriveAt(route.etaAt())
                    .provider(route.provider())
                    .legs(toJson(route.legs()))
                    .degraded(toJson("stub".equals(route.provider())
                            ? List.of("route_provider_fallback") : List.of()))
                    .rawRef(route.rawRef())
                    .expiresAt(expiresAt)
                    .build()));
        }
        return saved.stream().map(RouteSearchResponse::from).toList();
    }

    /** EventService가 event 저장과 같은 트랜잭션에서 호출한다. */
    @Transactional
    public SelectedRouteSearch consume(UUID userId, UUID routeOptionId) {
        RouteSearchOption selected = routeSearchOptionRepository.findOwnedForUpdate(routeOptionId, userId)
                // 다른 사용자의 키인지 구분하지 않아 UUID 열거를 막는다.
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROUTE_OPTION_NOT_FOUND", "경로 후보를 찾을 수 없습니다."));
        Instant now = Instant.now();
        if (!selected.getExpiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.GONE, "ROUTE_OPTION_EXPIRED", "경로 후보가 만료되었습니다. 다시 검색해 주세요.");
        }
        if (selected.getConsumedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ROUTE_OPTION_ALREADY_USED", "이미 일정 생성에 사용한 경로 후보입니다.");
        }

        List<RouteSearchOption> options = routeSearchOptionRepository
                .findBySearchSessionIdAndUserIdOrderByRouteRankAsc(selected.getSearchSessionId(), userId);
        selected.setConsumedAt(now);
        return SelectedRouteSearch.from(selected, options);
    }

    @Transactional
    public void bindToPlan(UUID userId, UUID routeOptionId, UUID planId) {
        RouteSearchOption option = routeSearchOptionRepository.findOwnedForUpdate(routeOptionId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROUTE_OPTION_NOT_FOUND", "경로 후보를 찾을 수 없습니다."));
        option.setConsumedPlanId(planId);
    }

    private Origin resolveAndValidateOrigin(UUID userId, RouteSearchRequest request) {
        validateCoordinatePair("originLat", request.originLat(), "originLng", request.originLng(), request.originPlaceId() == null);
        if (request.originLat() != null) {
            validateCoordinateBounds("originLat", request.originLat(), -90, 90);
            validateCoordinateBounds("originLng", request.originLng(), -180, 180);
        }
        if (request.originPlaceId() == null) {
            return new Origin(null, "현재 위치", request.originLat(), request.originLng());
        }
        UserPlace place = userPlaceRepository.findByPlaceIdAndUserId(request.originPlaceId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "출발 장소를 찾을 수 없습니다."));
        // 저장된 장소를 기준으로 경로를 계산한다. 클라이언트가 함께 보낸 좌표는 지도 렌더용 힌트일 뿐
        // 권한 있는 place의 실제 좌표를 바꾸지 못한다.
        return new Origin(
                place.getPlaceId(), place.getPlaceName(),
                placeCoordinateCodec.decode(place.getLatEnc()), placeCoordinateCodec.decode(place.getLngEnc()));
    }

    private String normalizeAnchorMode(String anchorMode) {
        String normalized = anchorMode == null || anchorMode.isBlank() ? DEFAULT_ANCHOR_MODE : anchorMode;
        if (!DEFAULT_ANCHOR_MODE.equals(normalized) && !"depart_at".equals(normalized)) {
            throw validationError("anchorMode는 arrive_by 또는 depart_at이어야 합니다.");
        }
        return normalized;
    }

    private void validateCoordinatePair(String firstName, Double first, String secondName, Double second, boolean required) {
        if ((first == null) != (second == null) || (required && first == null)) {
            throw validationError(firstName + "과 " + secondName + "는 함께 지정해야 합니다.");
        }
    }

    private void validateCoordinateBounds(String name, Double value, double min, double max) {
        if (value == null || !Double.isFinite(value) || value < min || value > max) {
            throw validationError(name + " 값이 올바르지 않습니다.");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private ApiException validationError(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", message);
    }

    private record Origin(UUID placeId, String name, double lat, double lng) {
    }
}
