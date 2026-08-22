package com.hq.backend.plan;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

// PlanCreationIntegrationTest와 같은 고정 응답 fake-engine 패턴 — 여기는 생성 이후의
// 조회·재계산·수정·경로선택(§9~10)을 검증한다.
@SpringBootTest(properties = {
        "provider.kma.service-key=",
        "provider.airkorea.service-key=",
        "provider.airkorea.station-name="
})
@AutoConfigureMockMvc
class PlanControllerTest {

    private static HttpServer fakeEngine;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserPlaceRepository userPlaceRepository;

    @Autowired
    private PlaceCoordinateCodec placeCoordinateCodec;

    @BeforeAll
    static void startFakeEngine() throws IOException {
        fakeEngine = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeEngine.createContext("/internal/v1/plans/compute", exchange -> {
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
                       "appliedMinutes":0,"isSensitive":false,"reason":"강수 확률 높음"}],
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
    void 계획_상세_조회와_이벤트별_최신_계획_조회() throws Exception {
        Created created = createEventWithPlan();

        mockMvc.perform(get("/plans/" + created.planId).header("Authorization", "Bearer " + created.accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNo").value(1))
                .andExpect(jsonPath("$.planStatus").value("active"))
                .andExpect(jsonPath("$.checklist[0].itemName").value("우산"))
                .andExpect(jsonPath("$.checklist[0].reason").value("강수 확률 높음"))
                .andExpect(jsonPath("$.context.weatherProvider").value("stub"))
                .andExpect(jsonPath("$.degraded").isArray());

        mockMvc.perform(get("/events/" + created.eventId + "/plans/latest")
                        .header("Authorization", "Bearer " + created.accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(created.planId.toString()));
    }

    @Test
    void 경로를_다시_선택하면_새_리비전이_생성되고_이전_리비전은_superseded된다() throws Exception {
        Created created = createEventWithPlan();
        String routesResponse = mockMvc.perform(get("/plans/" + created.planId + "/routes")
                        .header("Authorization", "Bearer " + created.accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].routeOptionId").exists())
                .andReturn().getResponse().getContentAsString();
        String routeOptionId = JsonPath.read(routesResponse, "$[0].routeOptionId");

        mockMvc.perform(post("/plans/" + created.planId + "/routes/select")
                        .header("Authorization", "Bearer " + created.accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeOptionId\":\"" + routeOptionId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.planStatus").value("active"));

        mockMvc.perform(get("/plans/" + created.planId).header("Authorization", "Bearer " + created.accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planStatus").value("superseded"));
    }

    @Test
    void 사용자가_prepStartAt를_직접_수정하면_그값이_새_리비전에_반영된다() throws Exception {
        Created created = createEventWithPlan();

        mockMvc.perform(patch("/plans/" + created.planId)
                        .header("Authorization", "Bearer " + created.accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prepStartAt\":\"2026-08-20T12:10:00+09:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.prepStartAt").value("2026-08-20T03:10:00Z"));
    }

    @Test
    void 입력이_동일하면_재계산해도_리비전이_늘지_않는다() throws Exception {
        Created created = createEventWithPlan();

        mockMvc.perform(post("/events/" + created.eventId + "/plan/recalculate")
                        .header("Authorization", "Bearer " + created.accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.revisionNo").value(1));
    }

    @Test
    void 다른_사용자의_계획은_404() throws Exception {
        Created created = createEventWithPlan();
        String otherToken = signupAndLogin();

        mockMvc.perform(get("/plans/" + created.planId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PLAN_NOT_FOUND"));
    }

    private record Created(String accessToken, UUID eventId, UUID planId) {
    }

    private Created createEventWithPlan() throws Exception {
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
                .andReturn().getResponse().getContentAsString();

        UUID eventId = UUID.fromString(JsonPath.read(response, "$.eventId").toString());
        UUID planId = UUID.fromString(JsonPath.read(response, "$.plan.planId").toString());
        return new Created(accessToken, eventId, planId);
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
