package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.BusyBlockResponse;
import com.hq.backend.calendar.dto.CalendarConnectionResponse;
import com.hq.backend.calendar.dto.CalendarConnectionSummaryResponse;
import com.hq.backend.calendar.dto.CalendarSourcePatchRequest;
import com.hq.backend.calendar.dto.CalendarSourceResponse;
import com.hq.backend.calendar.dto.CalendarConnectionStatusResponse;
import com.hq.backend.calendar.dto.ConnectCalendarRequest;
import com.hq.backend.calendar.dto.DensityResponse;
import com.hq.backend.calendar.dto.GoogleBusyEventsResponse;
import com.hq.backend.calendar.dto.GoogleTokenResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.event.EventRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

// ERD v3 전환에 맞춘 최소 수정 — calendar_connections.scope 컬럼이 없어지고
// external_account_id(NOT NULL)가 새로 필요해졌다. id_token(구글이 openid 스코프일 때
// 같이 주는 JWT)의 sub 클레임을 꺼내 계정 식별자로 쓴다 — 이메일보다 안정적인 계정
// 식별자이기 때문이다. 로그인 인증이 아니라 "어느 구글 계정과 연결됐는지" 표시용이다.
// TODO(박찬): CALENDAR_SOURCE(개별 캘린더 목록) 분리, 실제 계정 검증 강화는 아직 반영 안 됨.
@Service
@RequiredArgsConstructor
public class CalendarService {

    private static final String PROVIDER_GOOGLE = "google";
    private static final String SOURCE_GOOGLE = "google";
    private static final String SOURCE_USER_EVENT = "user_event";
    private static final String BUSY_FIELDS = "items(start(dateTime),end(dateTime)),nextPageToken";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");
    private static final java.util.regex.Pattern SUB_CLAIM =
            java.util.regex.Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]+)\"");

    private final CalendarConnectionRepository calendarConnectionRepository;
    private final CalendarSourceRepository calendarSourceRepository;
    private final EventRepository eventRepository;
    private final BytesEncryptor calendarTokenEncryptor;
    private final RestClient restClient;
    private final TransactionTemplate transactionTemplate;

    @Value("${oauth.google.token-url}")
    private String googleTokenUrl;

    @Value("${oauth.google.client-id}")
    private String googleClientId;

    @Value("${oauth.google.client-secret}")
    private String googleClientSecret;

    @Value("${oauth.google.calendar-events-url}")
    private String googleCalendarEventsUrl;

    // 구글과의 토큰 교환(외부 호출)을 트랜잭션 밖에서 먼저 끝내고, DB 쓰기만
    // TransactionTemplate으로 짧게 감싼다 — 네트워크 호출 동안 DB 커넥션을 붙잡지 않기 위해서.
    public CalendarConnectionResponse connect(UUID userId, ConnectCalendarRequest request) {
        GoogleTokenResponse tokenResponse = exchangeAuthCode(request.authCode());
        return transactionTemplate.execute(status -> persistConnection(userId, tokenResponse));
    }

    private CalendarConnectionResponse persistConnection(UUID userId, GoogleTokenResponse tokenResponse) {
        Optional<CalendarConnection> existing =
                calendarConnectionRepository.findByUserIdAndProvider(userId, PROVIDER_GOOGLE);
        Instant now = Instant.now();

        // 이미 동의한 앱에 대한 재교환이면 구글이 refresh_token을 안 줄 수 있다 — 기존 연결이
        // 있으면 그 토큰을 그대로 쓰고 connectedAt만 갱신, 없으면 재동의를 요구한다.
        if (tokenResponse.refreshToken() == null) {
            CalendarConnection connection = existing.orElseThrow(() -> new ApiException(
                    HttpStatus.BAD_REQUEST, "REFRESH_TOKEN_MISSING",
                    "구글이 refresh_token을 반환하지 않았고 기존 연결도 없습니다. 동의 화면을 다시 띄워주세요."));
            extractExternalAccountId(tokenResponse.idToken()).ifPresent(connection::setExternalAccountId);
            connection.setConnectedAt(now);
            connection.setRevokedAt(null);
            return toResponse(connection);
        }

        String externalAccountId = extractExternalAccountId(tokenResponse.idToken())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "GOOGLE_ACCOUNT_ID_MISSING",
                        "구글 계정 식별자를 확인할 수 없습니다. openid 동의가 필요합니다."));

        byte[] encrypted =
                calendarTokenEncryptor.encrypt(tokenResponse.refreshToken().getBytes(StandardCharsets.UTF_8));

        CalendarConnection connection = existing.orElseGet(() -> CalendarConnection.builder()
                .userId(userId)
                .provider(PROVIDER_GOOGLE)
                .externalAccountId(externalAccountId)
                .build());
        connection.setExternalAccountId(externalAccountId);
        connection.setRefreshTokenEnc(encrypted);
        connection.setConnectedAt(now);
        connection.setRevokedAt(null);

        if (existing.isEmpty()) {
            calendarConnectionRepository.save(connection);
        }

        return toResponse(connection);
    }

    // id_token(JWT)의 payload(2번째 세그먼트)를 base64url 디코드해 sub 클레임만 꺼낸다.
    // 서명 검증은 하지 않는다 — 이 토큰은 방금 구글 토큰 엔드포인트와의 TLS 통신으로 직접
    // 받은 것이라(탈취 경로가 없음) 계정 표시용으로만 쓰는 이 맥락에서는 충분하다.
    // ponytail: sub 값 하나만 필요해서 정규식으로 뽑는다 — 전체 JSON 파싱용 라이브러리를
    // 새로 추가할 정도는 아니다.
    private Optional<String> extractExternalAccountId(String idToken) {
        if (idToken == null) {
            return Optional.empty();
        }
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                return Optional.empty();
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            var matcher = SUB_CLAIM.matcher(payload);
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Transactional
    public void disconnect(UUID userId) {
        CalendarConnection connection = calendarConnectionRepository
                .findByUserIdAndProvider(userId, PROVIDER_GOOGLE)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "CALENDAR_NOT_CONNECTED", "연결된 구글 캘린더가 없습니다."));
        connection.setRevokedAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public CalendarConnectionStatusResponse getGoogleConnectionStatus(UUID userId) {
        boolean connected = calendarConnectionRepository.findByUserIdAndProvider(userId, PROVIDER_GOOGLE)
                .filter(connection -> connection.getRevokedAt() == null)
                .isPresent();
        return new CalendarConnectionStatusResponse(connected);
    }

    @Transactional(readOnly = true)
    public List<CalendarConnectionSummaryResponse> listConnections(UUID userId) {
        List<CalendarConnection> connections =
                calendarConnectionRepository.findByUserIdAndRevokedAtIsNullOrderByConnectedAtDesc(userId);
        Map<UUID, List<CalendarSource>> sourcesByConnection = connections.isEmpty() ? Map.of()
                : calendarSourceRepository.findByCalendarConnectionIdInAndDeletedAtIsNull(connections.stream()
                                .map(CalendarConnection::getCalendarConnectionId).toList())
                        .stream().sorted(Comparator.comparing(CalendarSource::isDefault).reversed()
                                .thenComparing(CalendarSource::getDisplayName))
                        .collect(Collectors.groupingBy(CalendarSource::getCalendarConnectionId));
        return connections.stream()
                .map(connection -> new CalendarConnectionSummaryResponse(connection.getCalendarConnectionId(),
                        connection.getProvider(), connection.getExternalAccountId(), connection.getConnectedAt(),
                        connection.getLastSyncedAt(), sourcesByConnection
                                .getOrDefault(connection.getCalendarConnectionId(), List.of())
                                .stream().map(CalendarSourceResponse::from).toList()))
                .toList();
    }

    @Transactional
    public CalendarSourceResponse updateSource(UUID userId, UUID sourceId, CalendarSourcePatchRequest request) {
        CalendarSource source = findOwnedSource(userId, sourceId);
        source.setSyncEnabled(request.syncEnabled());
        return CalendarSourceResponse.from(source);
    }

    @Transactional
    public CalendarSourceResponse selectDefaultSource(UUID userId, UUID sourceId) {
        CalendarSource selected = findOwnedSource(userId, sourceId);
        if (!selected.isWritable()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CALENDAR_SOURCE_NOT_WRITABLE",
                    "기본 기록 캘린더는 쓰기 가능한 소스여야 합니다.");
        }
        calendarSourceRepository.findByCalendarConnectionIdAndDeletedAtIsNullOrderByIsDefaultDescDisplayNameAsc(
                selected.getCalendarConnectionId()).forEach(source -> source.setDefault(false));
        selected.setDefault(true);
        return CalendarSourceResponse.from(selected);
    }

    @Transactional(readOnly = true)
    public CalendarSource requireWritableSource(UUID userId, UUID sourceId) {
        CalendarSource source = findOwnedSource(userId, sourceId);
        if (!source.isWritable()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CALENDAR_SOURCE_NOT_WRITABLE",
                    "쓰기 가능한 캘린더 소스가 아닙니다.");
        }
        return source;
    }

    private CalendarSource findOwnedSource(UUID userId, UUID sourceId) {
        return calendarSourceRepository.findOwnedActive(sourceId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CALENDAR_SOURCE_NOT_FOUND",
                        "캘린더 소스를 찾을 수 없습니다."));
    }

    public DensityResponse getDensity(UUID userId, LocalDate date) {
        Instant rangeStart = date.atStartOfDay(DEFAULT_ZONE).toInstant();
        Instant rangeEnd = date.plusDays(1).atStartOfDay(DEFAULT_ZONE).toInstant();

        GoogleFetchResult googleResult = fetchGoogleBusyBlocks(userId, rangeStart, rangeEnd);

        List<BusyBlockResponse> blocks = new ArrayList<>();
        blocks.addAll(googleResult.blocks());
        blocks.addAll(fetchUserEventBusyBlocks(userId, rangeStart, rangeEnd));
        blocks.sort(Comparator.comparing(BusyBlockResponse::startsAt));
        return new DensityResponse(googleResult.synced(), blocks);
    }

    private List<BusyBlockResponse> fetchUserEventBusyBlocks(UUID userId, Instant rangeStart, Instant rangeEnd) {
        return eventRepository
                .findByUserIdAndStartsAtLessThanAndEndsAtGreaterThan(userId, rangeEnd, rangeStart)
                .stream()
                .map(e -> new BusyBlockResponse(e.getStartsAt(), e.getEndsAt(), SOURCE_USER_EVENT))
                .toList();
    }

    private GoogleFetchResult fetchGoogleBusyBlocks(UUID userId, Instant rangeStart, Instant rangeEnd) {
        Optional<CalendarConnection> connection =
                calendarConnectionRepository.findByUserIdAndProvider(userId, PROVIDER_GOOGLE);
        if (connection.isEmpty() || connection.get().getRevokedAt() != null) {
            return new GoogleFetchResult(true, List.of());
        }

        try {
            byte[] decrypted = calendarTokenEncryptor.decrypt(connection.get().getRefreshTokenEnc());
            Optional<String> accessToken = refreshAccessToken(new String(decrypted, StandardCharsets.UTF_8));
            if (accessToken.isEmpty()) {
                return new GoogleFetchResult(false, List.of());
            }

            List<BusyBlockResponse> blocks = new ArrayList<>();
            Set<String> seenPageTokens = new HashSet<>();
            String pageToken = null;
            while (true) {
                GoogleBusyEventsResponse response = restClient.get()
                        .uri(buildGoogleBusyUri(rangeStart, rangeEnd, pageToken))
                        .header("Authorization", "Bearer " + accessToken.get())
                        .retrieve()
                        .body(GoogleBusyEventsResponse.class);
                if (response == null || response.items() == null) {
                    return new GoogleFetchResult(false, List.of());
                }
                response.items().stream()
                        .filter(item -> item.start() != null && item.start().dateTime() != null
                                && item.end() != null && item.end().dateTime() != null)
                        .map(item -> new BusyBlockResponse(
                                item.start().dateTime(), item.end().dateTime(), SOURCE_GOOGLE))
                        .forEach(blocks::add);
                String nextPageToken = response.nextPageToken();
                if (nextPageToken == null || nextPageToken.isBlank()) {
                    break;
                }
                if (!seenPageTokens.add(nextPageToken)) {
                    return new GoogleFetchResult(false, List.of());
                }
                pageToken = nextPageToken;
            }
            return new GoogleFetchResult(true, blocks);
        } catch (RestClientException | IllegalStateException e) {
            return new GoogleFetchResult(false, List.of());
        }
    }

    private URI buildGoogleBusyUri(Instant rangeStart, Instant rangeEnd, String pageToken) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(googleCalendarEventsUrl)
                .queryParam("timeMin", rangeStart)
                .queryParam("timeMax", rangeEnd)
                .queryParam("singleEvents", true)
                .queryParam("orderBy", "startTime")
                .queryParam("fields", BUSY_FIELDS);
        if (pageToken == null) {
            return builder.encode().build().toUri();
        }
        return builder.queryParam("pageToken", "{pageToken}")
                .encode()
                .buildAndExpand(pageToken)
                .toUri();
    }

    private record GoogleFetchResult(boolean synced, List<BusyBlockResponse> blocks) {
    }

    private Optional<String> refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", googleClientId);
        form.add("client_secret", googleClientSecret);
        form.add("grant_type", "refresh_token");

        try {
            GoogleTokenResponse response = postTokenForm(form);
            return response == null ? Optional.empty() : Optional.ofNullable(response.accessToken());
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    private GoogleTokenResponse exchangeAuthCode(String authCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", authCode);
        form.add("client_id", googleClientId);
        form.add("client_secret", googleClientSecret);
        form.add("redirect_uri", ""); // serverAuthCode 플로우(모바일)는 빈 문자열
        form.add("grant_type", "authorization_code");

        GoogleTokenResponse response;
        try {
            response = postTokenForm(form);
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CALENDAR_CONNECT_FAILED", "구글과 토큰 교환에 실패했습니다.");
        }

        if (response == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CALENDAR_CONNECT_FAILED", "구글과 토큰 교환에 실패했습니다.");
        }
        return response;
    }

    private GoogleTokenResponse postTokenForm(MultiValueMap<String, String> form) {
        return restClient.post()
                .uri(googleTokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(GoogleTokenResponse.class);
    }

    private CalendarConnectionResponse toResponse(CalendarConnection connection) {
        ensurePrimarySource(connection);
        return new CalendarConnectionResponse(
                connection.getProvider(), connection.getExternalAccountId(), connection.getConnectedAt());
    }

    private void ensurePrimarySource(CalendarConnection connection) {
        calendarSourceRepository.insertDefaultSourceIfAbsent(
                connection.getCalendarConnectionId(), "primary", "내 캘린더");
    }
}
