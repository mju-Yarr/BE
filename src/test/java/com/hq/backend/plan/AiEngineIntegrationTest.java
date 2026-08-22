package com.hq.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hq.backend.metrics.ProductEventRepository;
import com.hq.backend.personalization.EventExecution;
import com.hq.backend.personalization.EventExecutionRepository;
import com.hq.backend.personalization.EventDelayReasonRepository;
import com.hq.backend.personalization.UserPrepEstimate;
import com.hq.backend.personalization.UserPrepEstimateRepository;
import com.hq.backend.place.PlaceCoordinateCodec;
import com.hq.backend.place.UserPlace;
import com.hq.backend.place.UserPlaceRepository;
import com.hq.backend.wellness.PlanWellnessActionRepository;
import com.hq.backend.wellness.PlanWellnessScore;
import com.hq.backend.wellness.PlanWellnessScoreRepository;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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

// PR #105(계약 동결) 이후 실제로 Wellness/Personalization 엔진을 붙인 배선 검증.
// 세 엔진 모두 같은 FastAPI 앱이라 fake HttpServer 하나에 컨텍스트 3개를 등록한다.
@SpringBootTest(properties = {
        "provider.kma.service-key=",
        "provider.airkorea.service-key=",
        "provider.airkorea.station-name="
})
@AutoConfigureMockMvc
class AiEngineIntegrationTest {

    private static HttpServer fakeEngine;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserPlaceRepository userPlaceRepository;

    @Autowired
    private PlaceCoordinateCodec placeCoordinateCodec;

    @Autowired
    private PlanContextRepository planContextRepository;

    @Autowired
    private PlanWellnessScoreRepository planWellnessScoreRepository;

    @Autowired
    private PlanWellnessActionRepository planWellnessActionRepository;

    @Autowired
    private UserPrepEstimateRepository userPrepEstimateRepository;

    @Autowired
    private EventExecutionRepository eventExecutionRepository;

    @Autowired
    private EventDelayReasonRepository eventDelayReasonRepository;

    @Autowired
    private ProductEventRepository productEventRepository;

    @BeforeAll
    static void startFakeEngine() throws IOException {
        fakeEngine = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeEngine.createContext("/internal/v1/plans/compute", exchange -> respond(exchange, """
                {"prepStartAt":"2026-08-20T12:25:00+09:00",
                 "recommendedDepartAt":"2026-08-20T13:05:00+09:00",
                 "targetArriveAt":"2026-08-20T13:50:00+09:00",
                 "breakdown":{"estimatedPrepMinutes":30,"extraPrepMinutes":0,
                   "personalRoutineMinutes":0,"travelMinutes":20,
                   "trafficBufferMinutes":5,"arrivalBufferMinutes":10},
                 "reasons":[],"checklist":[],
                 "feasible":true,"predictionConfidence":"high","degraded":[],
                 "calcVersion":"test-engine-1.0.0"}
                """));
        fakeEngine.createContext("/internal/v1/wellness/evaluate", exchange -> respond(exchange, """
                {"wisScore":72,"wisBand":"high",
                 "normalizedLoads":{"uvLoad":0.85,"pmLoad":0.15,"thermalLoad":0.70,
                   "outdoorLoad":0.46,"interestMultiplier":1.15},
                 "quantized":{"rain":"none","uv":"high","pm":"bad","temp":"hot","tempSwing":false},
                 "actions":[{"wellnessTopic":"pm","actionCode":"pm_mask",
                   "actionLabel":"마스크 확인","displayRank":1,"reason":"대기질 나쁨 · 야외 55분",
                   "mergedWithPrepItem":false,"mergedItemId":null}],
                 "eventArmed":true,"armedActionCode":"pm_recheck","armingBlockedBy":[],
                 "weightVersion":"m3-wellness-1.0.0","contractVersion":"m0-v1","degraded":[],
                 "futureM3Field":{"safeToIgnore":true}}
                """));
        fakeEngine.createContext("/internal/v1/wellness/rush-load", exchange -> respond(exchange, """
                {"eventId":"ignored-by-test","rushLoadScore":42,
                 "prepDelayNorm":0.25,"departDelayNorm":0.50,"criticalAlertNorm":0.0,
                 "weightVersion":"m3-wellness-1.0.0","contractVersion":"m0-v1"}
                """));
        fakeEngine.createContext("/internal/v1/personalization/adjust", exchange -> respond(exchange, """
                {"cause":"prep_overrun","adjustedKnob":"prep_estimate",
                 "previousValue":30.0,"newValue":34.5,
                 "adjustmentReason":"EMA 보정: 실제 준비 시간 47분","excludedFromLearning":false,
                 "modelVersion":"m2-personalization-1.0.0","contractVersion":"m0-v1",
                 "causeConfidence":0.667,
                 "candidates":[
                   {"cause":"prep_overrun","confidence":0.667,"signalMinutes":10.0},
                   {"cause":"traffic","confidence":0.333,"signalMinutes":5.0}],
                 "exclusionReasons":[],"degraded":["prep_finish_unknown"]}
                """));
        fakeEngine.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
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
    void 계획_생성시_웰니스_점수와_행동이_저장된다() throws Exception {
        Created created = createEventWithPlan();

        PlanWellnessScore score = planWellnessScoreRepository.findById(created.planId()).orElseThrow();
        assertThat(score.getWisScore()).isEqualTo((short) 72);
        assertThat(score.getWisBand()).isEqualTo("high");

        PlanContext context = planContextRepository.findById(created.planId()).orElseThrow();
        assertThat(context.getPm25()).isEqualTo(20);
        assertThat(context.getAirGrade()).isEqualTo("moderate");
        assertThat(context.getFeelsLikeMin()).isEqualByComparingTo("17.0");
        assertThat(context.getFeelsLikeMax()).isEqualByComparingTo("27.0");
        assertThat(context.getAirProvider()).isEqualTo("stub-air");

        assertThat(planWellnessActionRepository.findAll())
                .anyMatch(a -> a.getPlanId().equals(created.planId())
                        && "pm".equals(a.getWellnessTopic())
                        && "pm_mask".equals(a.getActionCode()));

        assertThat(productEventRepository.findAll())
                .anyMatch(e -> "plan_created".equals(e.getEventName()) && created.userId().equals(e.getUserId()));
    }

    @Test
    void 도착_액션을_보내면_개인화_보정이_적용되고_지연사유가_기록된다() throws Exception {
        Created created = createEventWithPlan();
        userPrepEstimateRepository.save(UserPrepEstimate.builder()
                .userId(created.userId())
                .scopeType("global")
                .estimatedMinutes(30)
                .sampleCount(1)
                .confidence(new BigDecimal("0.60"))
                .modelVersion("ema-v1")
                .validFrom(Instant.now().minusSeconds(3600))
                .build());

        String body = """
                {"actions":[{"actionType":"ARRIVED","actionSource":"GEO",
                  "deviceTs":"2026-08-20T13:50:00+09:00","clientEventId":"%s","confidence":0.9}]}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/plans/" + created.planId() + "/actions")
                        .header("Authorization", "Bearer " + created.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        List<UserPrepEstimate> history = userPrepEstimateRepository
                .findByUserIdAndScopeTypeOrderByValidFromDesc(created.userId(), "global");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getEstimatedMinutes()).isEqualTo(35); // Math.round(34.5)
        assertThat(history.get(0).isColdStartAdjusted()).isTrue();
        assertThat(history.get(0).getAdjustmentReason()).contains("EMA 보정");
        assertThat(history.get(1).getValidTo()).isNotNull();

        assertThat(eventDelayReasonRepository.findByEventId(created.eventId()))
                .anyMatch(r -> "prep_overrun".equals(r.getReasonCode())
                        && new BigDecimal("0.667").compareTo(r.getConfidence()) == 0);
        assertThat(eventDelayReasonRepository.findByEventId(created.eventId()))
                .anyMatch(r -> "traffic".equals(r.getReasonCode())
                        && new BigDecimal("0.333").compareTo(r.getConfidence()) == 0);

        EventExecution execution = eventExecutionRepository.findById(created.eventId()).orElseThrow();
        assertThat(execution.getRushLoadScore()).isEqualTo((short) 42);
        assertThat(execution.getPrepDelayNorm()).isEqualByComparingTo("0.25");
        assertThat(execution.getDepartDelayNorm()).isEqualByComparingTo("0.50");
        assertThat(execution.getCriticalAlertNorm()).isEqualByComparingTo("0.0");

        assertThat(productEventRepository.findAll())
                .anyMatch(e -> "arrival_result".equals(e.getEventName()) && created.userId().equals(e.getUserId()));
        assertThat(productEventRepository.findAll())
                .anyMatch(e -> "personalization_adjusted".equals(e.getEventName()) && created.userId().equals(e.getUserId()));
    }

    private record Created(String accessToken, UUID userId, UUID eventId, UUID planId) {
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
        return new Created(accessToken, userId, eventId, planId);
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
