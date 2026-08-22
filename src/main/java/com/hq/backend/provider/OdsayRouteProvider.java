package com.hq.backend.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * ODsay 대중교통 길찾기(v1.8) 어댑터.
 *
 * <p>ODsay는 소요 시간을 분 단위로 반환하지만 내부 RouteOption은 초 단위를 사용한다.
 * API 키가 설정된 경우에만 기본 RouteProvider가 되며, 통신·스키마 오류 시에는 기존
 * StubRouteProvider로 안전하게 저하한다. 정상 응답에서 경로가 없는 경우는 실제 검색 결과이므로
 * 빈 목록을 반환한다.</p>
 */
@Component
@Primary
@ConditionalOnExpression("!'${provider.odsay.api-key:}'.isBlank()")
public class OdsayRouteProvider implements RouteProvider {

    private static final Logger log = LoggerFactory.getLogger(OdsayRouteProvider.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;
    private final StubRouteProvider fallbackProvider;
    private final String apiKey;
    private final String routeUrl;

    public OdsayRouteProvider(
            @Qualifier("odsayRestClient") RestClient restClient,
            StubRouteProvider fallbackProvider,
            @org.springframework.beans.factory.annotation.Value("${provider.odsay.api-key}") String apiKey,
            @org.springframework.beans.factory.annotation.Value("${provider.odsay.route-url}") String routeUrl) {
        this.restClient = restClient;
        this.fallbackProvider = fallbackProvider;
        this.apiKey = apiKey;
        this.routeUrl = routeUrl;
    }

    @Override
    public List<RouteOption> search(GeoPoint origin, GeoPoint dest, String anchor, Instant at) {
        if (!isValidCoordinate(origin) || !isValidCoordinate(dest)) {
            log.warn("[ODsay] 유효하지 않은 좌표로 경로 검색을 생략합니다.");
            return List.of();
        }

        try {
            URI uri = UriComponentsBuilder.fromUriString(routeUrl)
                    .queryParam("apiKey", apiKey)
                    .queryParam("SX", origin.lng())
                    .queryParam("SY", origin.lat())
                    .queryParam("EX", dest.lng())
                    .queryParam("EY", dest.lat())
                    .queryParam("OPT", 0)
                    .queryParam("SearchType", 0)
                    .queryParam("SearchPathType", 0)
                    .encode()
                    .build()
                    .toUri();
            String responseBody = restClient.get().uri(uri).retrieve().body(String.class);
            JsonNode response = objectMapper.readTree(responseBody);
            return parseResponse(response, anchor, at);
        } catch (RestClientException | JsonProcessingException | IllegalStateException | ArithmeticException e) {
            // 일부 HTTP 예외 메시지에는 요청 URL이 포함될 수 있으므로 apiKey를 보호하기 위해 타입만 기록한다.
            log.warn("[ODsay] 경로 API 응답을 사용할 수 없어 Stub 경로로 대체합니다: {}", e.getClass().getSimpleName());
            return fallbackProvider.search(origin, dest, anchor, at);
        }
    }

    private List<RouteOption> parseResponse(JsonNode response, String anchor, Instant at) {
        if (response == null || response.has("error")) {
            throw new IllegalStateException("ODsay 오류 응답");
        }
        JsonNode paths = response.path("result").path("path");
        if (paths.isMissingNode() || !paths.isArray()) {
            throw new IllegalStateException("ODsay path 배열 누락");
        }
        if (paths.isEmpty()) {
            return List.of();
        }

        List<RouteOption> options = new ArrayList<>();
        for (int index = 0; index < paths.size(); index++) {
            options.add(toRouteOption(paths.get(index), index, anchor, at));
        }
        return assignRanks(options);
    }

    private RouteOption toRouteOption(JsonNode path, int index, String anchor, Instant at) {
        JsonNode info = path.path("info");
        JsonNode subPaths = path.path("subPath");
        if (info.isMissingNode() || !subPaths.isArray()) {
            throw new IllegalStateException("ODsay 경로 상세 필드 누락");
        }

        int totalSec = minutesToSeconds(requiredNonNegativeInt(info, "totalTime"));
        int transfers = Math.addExact(
                requiredNonNegativeInt(info, "busTransitCount"),
                requiredNonNegativeInt(info, "subwayTransitCount"));
        List<Leg> legs = new ArrayList<>();
        int walkSec = 0;
        for (JsonNode subPath : subPaths) {
            Leg leg = toLeg(subPath);
            legs.add(leg);
            if ("WALK".equals(leg.mode())) {
                walkSec = Math.addExact(walkSec, leg.sec());
            }
        }
        int outdoorSec = outdoorSeconds(legs);

        String rawRef = info.path("mapObj").asText();
        if (rawRef.isBlank()) {
            rawRef = "odsay-path-" + index;
        }
        Instant departAt = "arrive_by".equals(anchor) ? at.minusSeconds(totalSec) : at;
        Instant arriveAt = "arrive_by".equals(anchor) ? at : at.plusSeconds(totalSec);
        return new RouteOption(
                UUID.randomUUID().toString(),
                "fastest",
                totalSec,
                walkSec,
                transfers,
                outdoorSec,
                List.copyOf(legs),
                departAt,
                arriveAt,
                "odsay",
                rawRef);
    }

    /**
     * WIS 야외 노출에는 실제 야외 도보만 반영한다. 지하철 바로 전·후의 3분 미만
     * WALK는 환승 통로로 본다. trafficType이 누락되거나 알 수 없으면 toLeg가 예외를
     * 발생시켜 전체 응답을 Stub으로 저하하므로, 판별 불가 구간을 야외로 과대 계상하지 않는다.
     */
    private int outdoorSeconds(List<Leg> legs) {
        int outdoorSec = 0;
        for (int index = 0; index < legs.size(); index++) {
            Leg leg = legs.get(index);
            if (!"WALK".equals(leg.mode()) || isIndoorSubwayTransferWalk(legs, index)) {
                continue;
            }
            outdoorSec = Math.addExact(outdoorSec, leg.sec());
        }
        return outdoorSec;
    }

    private boolean isIndoorSubwayTransferWalk(List<Leg> legs, int walkIndex) {
        Leg walk = legs.get(walkIndex);
        if (walk.sec() >= 3 * 60) {
            return false;
        }
        return (walkIndex > 0 && "SUBWAY".equals(legs.get(walkIndex - 1).mode()))
                || (walkIndex + 1 < legs.size() && "SUBWAY".equals(legs.get(walkIndex + 1).mode()));
    }

    private Leg toLeg(JsonNode subPath) {
        int trafficType = requiredNonNegativeInt(subPath, "trafficType");
        String mode = switch (trafficType) {
            case 1 -> "SUBWAY";
            case 2 -> "BUS";
            case 3 -> "WALK";
            default -> throw new IllegalStateException("알 수 없는 ODsay trafficType");
        };
        int seconds = minutesToSeconds(requiredNonNegativeInt(subPath, "sectionTime"));
        int distanceM = requiredNonNegativeDistance(subPath, "distance");
        return new Leg(mode, seconds, distanceM);
    }

    /**
     * 순위는 항상 고유하다. 같은 경로가 여러 최소 지표를 동시에 가지면 우선순위가 높은
     * fastest → least_walk → least_transfer만 남기고 나머지는 후보에서 축소한다.
     */
    private List<RouteOption> assignRanks(List<RouteOption> options) {
        if (options.isEmpty()) {
            return List.of();
        }

        int fastestIndex = indexOfMin(options, Comparator.comparingInt(RouteOption::totalSec));
        int leastWalkIndex = indexOfMin(options, Comparator.comparingInt(RouteOption::walkSec));
        int leastTransferIndex = indexOfMin(options, Comparator.comparingInt(RouteOption::transfers));

        boolean[] selected = new boolean[options.size()];
        List<RouteOption> ranked = new ArrayList<>();
        addRankedOption(ranked, options, selected, fastestIndex, "fastest");
        addRankedOption(ranked, options, selected, leastWalkIndex, "least_walk");
        addRankedOption(ranked, options, selected, leastTransferIndex, "least_transfer");
        return List.copyOf(ranked);
    }

    private void addRankedOption(
            List<RouteOption> ranked, List<RouteOption> options, boolean[] selected, int index, String rank) {
        if (selected[index]) {
            return;
        }
        selected[index] = true;
        RouteOption option = options.get(index);
        ranked.add(new RouteOption(
                option.id(), rank, option.totalSec(), option.walkSec(), option.transfers(), option.outdoorSec(),
                option.legs(), option.departAt(), option.etaAt(), option.provider(), option.rawRef()));
    }

    private int indexOfMin(List<RouteOption> options, Comparator<RouteOption> comparator) {
        int bestIndex = 0;
        for (int index = 1; index < options.size(); index++) {
            if (comparator.compare(options.get(index), options.get(bestIndex)) < 0) {
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private int requiredNonNegativeInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt() || value.asInt() < 0) {
            throw new IllegalStateException("ODsay 필수 숫자 필드 오류: " + field);
        }
        return value.asInt();
    }

    private int requiredNonNegativeDistance(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())
                || value.asDouble() < 0 || value.asDouble() > Integer.MAX_VALUE) {
            throw new IllegalStateException("ODsay 거리 필드 오류: " + field);
        }
        return (int) Math.round(value.asDouble());
    }

    private int minutesToSeconds(int minutes) {
        return Math.multiplyExact(minutes, 60);
    }

    private boolean isValidCoordinate(GeoPoint point) {
        return point != null
                && Double.isFinite(point.lat())
                && Double.isFinite(point.lng())
                && point.lat() >= -90.0 && point.lat() <= 90.0
                && point.lng() >= -180.0 && point.lng() <= 180.0;
    }
}
