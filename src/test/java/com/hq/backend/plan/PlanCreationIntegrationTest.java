package com.hq.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hq.backend.place.PlaceCoordinateCodec;
import com.hq.backend.place.UserPlace;
import com.hq.backend.place.UserPlaceRepository;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

// StubRouteProvider/StubEnvironmentProvider가 고정값을 반환하므로(테스트 환경 기본 빈),
// ai/plan-engine 자리에 JDK HttpServer로 고정 응답 스텁을 띄워 POST /events의 계획
// 자동 생성 전체 배선(경로 조회 -> 엔진 호출 -> plan_revision/route_option/plan_prep_item
// 저장)을 검증한다.
@SpringBootTest
@AutoConfigureMockMvc
class PlanCreationIntegrationTest {

    private static HttpServer fakeEngine;
    private static final AtomicReference<String> lastEngineRequest = new AtomicReference<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserPlaceRepository userPlaceRepository;

    @Autowired
    private PlaceCoordinateCodec placeCoordinateCodec;

    @Autowired
    private PlanRevisionRepository planRevisionRepository;

    @Autowired
    private RouteOptionRepository routeOptionRepository;

    @Autowired
    private PlanPrepItemRepository planPrepItemRepository;

    @BeforeAll
    static void startFakeEngine() throws IOException {
        fakeEngine = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeEngine.createContext("/internal/v1/plans/compute", exchange -> {
            lastEngineRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"prepStartAt":"2026-08-20T12:25:00+09:00",
                     "recommendedDepartAt":"2026-08-20T13:05:00+09:00",
                     "targetArriveAt":"2026-08-20T13:50:00+09:00",
                     "breakdown":{"estimatedPrepMinutes":30,"extraPrepMinutes":0,
                       "personalRoutineMinutes":0,"travelMinutes":20,
                       "trafficBufferMinutes":5,"arrivalBufferMinutes":10},
                     "reasons":[{"field":"estimatedPrepMinutes","source":"fallback",
                       "adjusted":false,"text":"준비 시간 추정이 없어 기본값 30분을 사용했습니다."}],
                     "checklist":[{"itemName":"우산","actionType":"carry","sourceType":"weather",
                       "appliedMinutes":0,"isSensitive":false,"reason":null}],
                     "feasible":true,"predictionConfidence":"high","degraded":[],
                     "calcVersion":"test-engine-1.0.0"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        fakeEngine.start();
    }

    @AfterAll
    static void stopFakeEngine() {
        fakeEngine.stop(0);
    }

    @DynamicPropertySource
    static void planEngineUrl(DynamicPropertyRegistry registry) {
        registry.add("plan-engine.base-url", () -> "http://localhost:" + fakeEngine.getAddress().getPort());
    }

    @Test
    void 목적지와_원점이_있으면_계획이_자동_생성되어_응답에_동봉된다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);

        UserPlace origin = userPlaceRepository.save(UserPlace.builder()
                .userId(userId)
                .placeType("home")
                .placeName("집")
                .address("서울시 어딘가")
                .latEnc(placeCoordinateCodec.encode(37.5))
                .lngEnc(placeCoordinateCodec.encode(127.0))
                .isPrimary(true)
                .build());

        String body = """
                {"startsAt":"2026-08-20T14:00:00+09:00","locationState":"REQUIRED_RESOLVED",
                 "sourceType":"MAP_SEARCH","destinationName":"강남역",
                 "destinationLat":37.498,"destinationLng":127.027,
                 "originPlaceId":"%s"}
                """.formatted(origin.getPlaceId());

        String response = mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plan.feasible").value(true))
                .andExpect(jsonPath("$.plan.predictionConfidence").value("high"))
                .andExpect(jsonPath("$.plan.breakdown.travelMinutes").value(20))
                .andExpect(jsonPath("$.plan.calcVersion").value("test-engine-1.0.0"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String planId = JsonPath.read(response, "$.plan.planId");
        assertThat(planRevisionRepository.findById(UUID.fromString(planId))).isPresent();
        assertThat(routeOptionRepository.findAll()).isNotEmpty();
        assertThat(planPrepItemRepository.findAll()).anyMatch(item -> "우산".equals(item.getItemNameSnapshot()));
    }

    @Test
    void depart_at_일정은_engine에_고정_출발시각을_전달한다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);
        UserPlace origin = userPlaceRepository.save(UserPlace.builder()
                .userId(userId).placeType("home").placeName("집").address("서울시 어딘가")
                .latEnc(placeCoordinateCodec.encode(37.5)).lngEnc(placeCoordinateCodec.encode(127.0))
                .isPrimary(true).build());
        String startsAt = "2026-08-22T09:30:00+09:00";
        String body = """
                {"startsAt":"%s","anchorMode":"depart_at","locationState":"REQUIRED_RESOLVED",
                 "sourceType":"MAP_SEARCH","destinationName":"강남역",
                 "destinationLat":37.498,"destinationLng":127.027,"originPlaceId":"%s"}
                """.formatted(startsAt, origin.getPlaceId());

        mockMvc.perform(post("/events").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorMode").value("depart_at"));

        assertThat(JsonPath.<String>read(lastEngineRequest.get(), "$.event.anchorMode")).isEqualTo("depart_at");
        assertThat(JsonPath.<String>read(lastEngineRequest.get(), "$.event.fixedDepartAt"))
                .isEqualTo("2026-08-22T00:30:00Z");
    }

    @Test
    void 원점_장소가_없으면_계획_없이_일정만_생성된다() throws Exception {
        String accessToken = signupAndLogin();
        String body = """
                {"startsAt":"2026-08-21T14:00:00+09:00","locationState":"REQUIRED_RESOLVED",
                 "sourceType":"MAP_SEARCH","destinationName":"강남역",
                 "destinationLat":37.498,"destinationLng":127.027}
                """;

        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plan").value(nullValue()));
    }

    private UUID extractUserId(String accessToken) {
        String payload = accessToken.split("\\.")[1];
        String decoded = new String(java.util.Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        return UUID.fromString(JsonPath.read(decoded, "$.sub").toString());
    }

    private String signupAndLogin() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());

        String loginBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        String response = mockMvc.perform(post("/auth/email/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(response, "$.accessToken");
    }
}
