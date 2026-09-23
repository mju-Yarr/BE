package com.hq.backend.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * TRD 11.2. 기상청 단기예보(TMP=기온, POP=강수확률) 실 연동.
 * PM10(에어코리아)과 자외선지수는 아직 미구현(-1로 채움).
 * API 호출 실패 시 기본값 반환 — 시간 계획은 정상, 웰니스만 생략 (TRD 11.5).
 *
 * provider.kma.service-key가 실제로 설정된 경우에만 빈으로 등록된다 — 인증키
 * 미발급 상태(로컬·테스트 기본값)에서는 여전히 StubEnvironmentProvider가 유일한
 * EnvironmentProvider 빈이라 @Primary 없이도 모호성이 없다.
 */
@Component("kmaWeatherEnvironmentProvider")
@ConditionalOnExpression("!'${provider.kma.service-key:}'.isBlank()")
public class KmaEnvironmentProvider implements EnvironmentProvider {

    private static final Logger log = LoggerFactory.getLogger(KmaEnvironmentProvider.class);

    // 기상청 단기예보 격자 변환 상수 (LCC DFS 투영, 공식 문서 고정값)
    private static final double RE = 6371.00877;
    private static final double GRID = 5.0;
    private static final double SLAT1 = 30.0;
    private static final double SLAT2 = 60.0;
    private static final double OLON = 126.0;
    private static final double OLAT = 38.0;
    private static final double XO = 43;
    private static final double YO = 136;

    private static final int[] BASE_HOURS = {2, 5, 8, 11, 14, 17, 20, 23};

    private final RestClient restClient;

    @Value("${provider.kma.service-key}")
    private String serviceKey;

    @Value("${provider.kma.forecast-url}")
    private String forecastUrl;

    public KmaEnvironmentProvider(@Qualifier("environmentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public EnvironmentSnapshot fetch(GeoPoint point, Instant at) {
        try {
            return doFetch(point, at);
        } catch (RestClientException | NullPointerException | java.util.NoSuchElementException e) {
            log.warn("[KMA] API 호출 실패, 기본값 반환: {}", e.getMessage());
            return new EnvironmentSnapshot(-1.0, -1, 22.0, 0, at, "kma_fallback");
        }
    }

    private EnvironmentSnapshot doFetch(GeoPoint point, Instant at) {
        int[] grid = toGrid(point.lat(), point.lng());
        String[] baseDateTime = baseDateTime(at);

        // serviceKey(디코딩 키)에는 '+'와 '='가 들어있는데, UriComponentsBuilder.encode()는
        // 이 둘을 query 컴포넌트에서 합법인 문자로 보고 그대로 통과시킨다. data.go.kr은
        // 원문 '+'를 공백으로 해석해 키가 어긋나 403 SERVICE_KEY_IS_NOT_REGISTERED_ERROR를
        // 돌려준다. 값만 직접 percent-encoding하고 build(true)로 재인코딩을 막는다.
        URI uri = UriComponentsBuilder.fromUriString(forecastUrl)
                .queryParam("serviceKey", URLEncoder.encode(serviceKey, StandardCharsets.UTF_8))
                .queryParam("dataType", "JSON")
                .queryParam("numOfRows", 100)
                .queryParam("pageNo", 1)
                .queryParam("base_date", baseDateTime[0])
                .queryParam("base_time", baseDateTime[1])
                .queryParam("nx", grid[0])
                .queryParam("ny", grid[1])
                .build(true)
                .toUri();

        KmaResponse response = restClient.get().uri(uri).retrieve().body(KmaResponse.class);
        List<KmaItem> items = response.response().body().items().item();

        double tempC = items.stream()
                .filter(item -> "TMP".equals(item.category()))
                .findFirst()
                .map(item -> Double.parseDouble(item.fcstValue()))
                .orElseThrow();
        int precipitationProb = items.stream()
                .filter(item -> "POP".equals(item.category()))
                .findFirst()
                .map(item -> Integer.parseInt(item.fcstValue()))
                .orElseThrow();

        // SKY는 없을 수 있다(발표 시각에 따라 항목이 빠짐) — 없으면 null로 두고 날씨 위젯이 생략한다.
        String sky = items.stream()
                .filter(item -> "SKY".equals(item.category()))
                .findFirst()
                .map(item -> skyFor(item.fcstValue()))
                .orElse(null);

        log.debug("[KMA] 조회 성공: grid=({},{}), TMP={}, POP={}, SKY={}",
                grid[0], grid[1], tempC, precipitationProb, sky);
        return new EnvironmentSnapshot(-1.0, -1, tempC, precipitationProb, at, "kma",
                null, null, null, null, null, sky, null, null);
    }

    /** 기상청 SKY 코드(1 맑음 / 3 구름많음 / 4 흐림). 2는 예보 항목에서 쓰이지 않는다. */
    private String skyFor(String fcstValue) {
        return switch (fcstValue == null ? "" : fcstValue.trim()) {
            case "1" -> "clear";
            case "3" -> "partly_cloudy";
            case "4" -> "cloudy";
            default -> null;
        };
    }

    private int[] toGrid(double lat, double lng) {
        double degRad = Math.PI / 180.0;
        double re = RE / GRID;
        double slat1 = SLAT1 * degRad;
        double slat2 = SLAT2 * degRad;
        double olon = OLON * degRad;
        double olat = OLAT * degRad;

        double sn = Math.tan(Math.PI * 0.25 + slat2 * 0.5) / Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sn = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn);
        double sf = Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sf = Math.pow(sf, sn) * Math.cos(slat1) / sn;
        double ro = Math.tan(Math.PI * 0.25 + olat * 0.5);
        ro = re * sf / Math.pow(ro, sn);

        double ra = Math.tan(Math.PI * 0.25 + lat * degRad * 0.5);
        ra = re * sf / Math.pow(ra, sn);
        double theta = lng * degRad - olon;
        if (theta > Math.PI) theta -= 2.0 * Math.PI;
        if (theta < -Math.PI) theta += 2.0 * Math.PI;
        theta *= sn;

        int x = (int) Math.floor(ra * Math.sin(theta) + XO + 0.5);
        int y = (int) Math.floor(ro - ra * Math.cos(theta) + YO + 0.5);
        return new int[]{x, y};
    }

    // 단기예보는 02/05/08/11/14/17/20/23시에 발표되고, API 제공은 발표 후 약 10분 뒤부터라
    // 10분을 빼고 직전 발표 시각을 기준으로 삼는다.
    private String[] baseDateTime(Instant at) {
        ZonedDateTime zoned = at.atZone(ZoneId.of("Asia/Seoul")).minusMinutes(10);
        int hour = zoned.getHour();
        int baseHour = BASE_HOURS[0];
        for (int h : BASE_HOURS) {
            if (h <= hour) {
                baseHour = h;
            }
        }
        var date = zoned.toLocalDate();
        if (hour < BASE_HOURS[0]) {
            date = date.minusDays(1);
            baseHour = BASE_HOURS[BASE_HOURS.length - 1];
        }
        String baseDate = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        String baseTime = String.format("%02d00", baseHour);
        return new String[]{baseDate, baseTime};
    }

    private record KmaResponse(KmaResponseBody response) {
    }

    private record KmaResponseBody(KmaBody body) {
    }

    private record KmaBody(KmaItems items) {
    }

    private record KmaItems(List<KmaItem> item) {
    }

    private record KmaItem(String category, @JsonProperty("fcstValue") String fcstValue) {
    }
}
