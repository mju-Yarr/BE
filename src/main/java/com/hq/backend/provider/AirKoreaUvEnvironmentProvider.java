package com.hq.backend.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Real environment enrichment for M3.
 *
 * <p>AirKorea's published PM grade is mapped to the engine vocabulary; raw PM values are never
 * reclassified by this service. KMA UV is optional because its area code is a deployment mapping,
 * not something that can safely be inferred from a point.</p>
 */
@Component
@Primary
@ConditionalOnExpression("!'${provider.kma.service-key:}'.isBlank() && !'${provider.airkorea.service-key:}'.isBlank() && !'${provider.airkorea.station-name:}'.isBlank()")
public class AirKoreaUvEnvironmentProvider implements EnvironmentProvider {

    private static final Logger log = LoggerFactory.getLogger(AirKoreaUvEnvironmentProvider.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter AIR_OBSERVED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final EnvironmentProvider weatherProvider;
    private final RestClient restClient;

    @Value("${provider.airkorea.service-key}")
    private String airKoreaServiceKey;

    @Value("${provider.airkorea.station-name}")
    private String stationName;

    @Value("${provider.airkorea.measurement-url}")
    private String measurementUrl;

    @Value("${provider.kma.uv.area-no:}")
    private String uvAreaNo;

    @Value("${provider.kma.uv.index-url}")
    private String uvIndexUrl;

    @Value("${provider.kma.service-key:}")
    private String kmaServiceKey;

    public AirKoreaUvEnvironmentProvider(
            @Qualifier("kmaWeatherEnvironmentProvider") EnvironmentProvider weatherProvider,
            @Qualifier("environmentRestClient") RestClient restClient) {
        this.weatherProvider = weatherProvider;
        this.restClient = restClient;
    }

    @Override
    public EnvironmentSnapshot fetch(GeoPoint point, Instant at) {
        EnvironmentSnapshot weather = weatherProvider.fetch(point, at);
        Optional<AirReading> air = fetchAir();
        Optional<Double> uv = fetchUv(at);

        return new EnvironmentSnapshot(
                uv.orElse(-1.0),
                air.map(AirReading::pm10).orElse(-1),
                weather.tempC(),
                weather.precipitationProb(),
                air.flatMap(AirReading::observedAt).orElse(at),
                weather.provider(),
                air.map(AirReading::pm25).orElse(null),
                air.map(AirReading::grade).orElse(null),
                weather.feelsLikeMinCelsius(),
                weather.feelsLikeMaxCelsius(),
                air.isPresent() ? "airkorea" : null,
                weather.sky(),
                air.map(AirReading::pm10Grade).orElse(null),
                air.map(AirReading::pm25Grade).orElse(null));
    }

    private Optional<AirReading> fetchAir() {
        try {
            // encode()는 '+'·'='를 query에서 합법인 문자로 보고 통과시켜, 그런 문자가 든
            // 서비스키를 쓰면 data.go.kr이 키를 오인한다(KmaEnvironmentProvider 주석 참고).
            // 값을 직접 percent-encoding하고 build(true)로 재인코딩을 막는다.
            URI uri = UriComponentsBuilder.fromUriString(measurementUrl)
                    .queryParam("serviceKey", URLEncoder.encode(airKoreaServiceKey, StandardCharsets.UTF_8))
                    .queryParam("returnType", "json")
                    .queryParam("numOfRows", 1)
                    .queryParam("pageNo", 1)
                    .queryParam("stationName", URLEncoder.encode(stationName, StandardCharsets.UTF_8))
                    .queryParam("dataTerm", "DAILY")
                    .build(true)
                    .toUri();
            AirKoreaResponse response = restClient.get().uri(uri).retrieve().body(AirKoreaResponse.class);
            AirKoreaItem item = response == null || response.response() == null || response.response().body() == null
                    || response.response().body().items() == null || response.response().body().items().isEmpty()
                    ? null : response.response().body().items().getFirst();
            if (item == null) {
                return Optional.empty();
            }
            Integer pm10 = parseInt(item.pm10Value());
            Integer pm25 = parseInt(item.pm25Value());
            String pm10Grade = providerGrade(item.pm10Grade()).orElse(null);
            String pm25Grade = providerGrade(item.pm25Grade()).orElse(null);
            // airGrade(엔진 입력)는 종전대로 PM2.5 우선 단일 값이다. 화면용으로 둘을 따로
            // 보여줘야 해서 원본 등급 두 개를 함께 싣는다.
            String grade = pm25Grade != null ? pm25Grade : pm10Grade;
            if (pm10 == null && pm25 == null && grade == null) {
                return Optional.empty();
            }
            return Optional.of(new AirReading(pm10 == null ? -1 : pm10, pm25, grade,
                    pm10Grade, pm25Grade, parseObservedAt(item.dataTime())));
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("[AIRKOREA] API 호출 실패; PM 입력을 degraded 처리합니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Double> fetchUv(Instant at) {
        if (uvAreaNo == null || uvAreaNo.isBlank() || kmaServiceKey == null || kmaServiceKey.isBlank()) {
            return Optional.empty();
        }
        try {
            String time = at.atZone(KST).format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
            URI uri = UriComponentsBuilder.fromUriString(uvIndexUrl)
                    .queryParam("serviceKey", URLEncoder.encode(kmaServiceKey, StandardCharsets.UTF_8))
                    .queryParam("dataType", "JSON")
                    .queryParam("areaNo", uvAreaNo)
                    .queryParam("time", time)
                    .build(true)
                    .toUri();
            KmaUvResponse response = restClient.get().uri(uri).retrieve().body(KmaUvResponse.class);
            KmaUvItem item = response == null || response.response() == null || response.response().body() == null
                    || response.response().body().items() == null || response.response().body().items().item() == null
                    || response.response().body().items().item().isEmpty()
                    ? null : response.response().body().items().item().getFirst();
            return item == null ? Optional.empty() : parseDouble(item.h0());
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("[KMA-UV] API 호출 실패; UV 입력을 degraded 처리합니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** AirKorea's own 1..4 grade taxonomy, never a threshold derived from PM concentration. */
    private Optional<String> providerGrade(String sourceGrade) {
        return switch (sourceGrade == null ? "" : sourceGrade.trim()) {
            case "1" -> Optional.of("good");
            case "2" -> Optional.of("moderate");
            case "3" -> Optional.of("bad");
            case "4" -> Optional.of("very_bad");
            default -> Optional.empty();
        };
    }

    private Integer parseInt(String value) {
        try {
            return value == null || value.isBlank() || "-".equals(value) ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Optional<Double> parseDouble(String value) {
        try {
            return value == null || value.isBlank() || "-".equals(value) ? Optional.empty()
                    : Optional.of(Double.valueOf(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<Instant> parseObservedAt(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty()
                    : Optional.of(LocalDateTime.parse(value.trim(), AIR_OBSERVED_AT).atZone(KST).toInstant());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private record AirReading(int pm10, Integer pm25, String grade, String pm10Grade, String pm25Grade,
            Optional<Instant> observedAt) {
    }

    private record AirKoreaResponse(AirKoreaEnvelope response) {
    }

    private record AirKoreaEnvelope(AirKoreaBody body) {
    }

    private record AirKoreaBody(List<AirKoreaItem> items) {
    }

    private record AirKoreaItem(
            String dataTime, String pm10Value, String pm25Value, String pm10Grade, String pm25Grade) {
    }

    private record KmaUvResponse(KmaUvEnvelope response) {
    }

    private record KmaUvEnvelope(KmaUvBody body) {
    }

    private record KmaUvBody(KmaUvItems items) {
    }

    private record KmaUvItems(List<KmaUvItem> item) {
    }

    private record KmaUvItem(@JsonProperty("h0") String h0) {
    }
}
