package com.hq.backend.wellness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hq.backend.event.Event;
import com.hq.backend.event.EventRepository;
import com.hq.backend.metrics.ProductEventRepository;
import com.hq.backend.personalization.EventExecution;
import com.hq.backend.personalization.EventExecutionRepository;
import com.hq.backend.plan.PlanRevision;
import com.hq.backend.plan.PlanRevisionRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// API 명세 §12.4.
@SpringBootTest
@AutoConfigureMockMvc
class DailySummaryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PlanRevisionRepository planRevisionRepository;

    @Autowired
    private PlanWellnessScoreRepository planWellnessScoreRepository;

    @Autowired
    private EventExecutionRepository eventExecutionRepository;

    @Autowired
    private ProductEventRepository productEventRepository;

    @Test
    void 관리한_일정이_있으면_요약_카드가_생성되고_이후_조회는_캐시된값을_반환한다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);
        LocalDate date = LocalDate.of(2026, 8, 16);
        Instant startsAt = date.atTime(14, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();

        Event event = eventRepository.save(Event.builder()
                .userId(userId).sourceType("internal").startsAt(startsAt)
                .isAllDay(false).locationState("not_required").autoManageExcluded(false)
                .status("closed").createdAt(startsAt)
                .build());
        PlanRevision revision = planRevisionRepository.save(PlanRevision.builder()
                .eventId(event.getEventId()).revisionNo(1)
                .prepStartAt(startsAt).recommendedDepartAt(startsAt).targetArriveAt(startsAt)
                .estimatedPrepMinutes(0).extraPrepMinutes(0).personalRoutineMinutes(0)
                .travelMinutes(0).trafficBufferMinutes(0).arrivalBufferMinutes(0)
                .feasible(true).reasons("[]").degraded("[]")
                .predictionConfidence("high").planStatus("active").calcVersion("test")
                .createdAt(startsAt)
                .build());
        planWellnessScoreRepository.save(PlanWellnessScore.builder()
                .planId(revision.getPlanId())
                .uvLoad(BigDecimal.ONE).pmLoad(BigDecimal.ZERO).thermalLoad(BigDecimal.ZERO)
                .outdoorLoad(BigDecimal.ONE).interestMultiplier(BigDecimal.ONE)
                .wisScore((short) 80).wisBand("high").weightVersion("w1")
                .calculatedAt(startsAt)
                .build());
        eventExecutionRepository.save(EventExecution.builder()
                .eventId(event.getEventId()).finalPlanId(revision.getPlanId())
                .arrivalResult("on_time").resultSource("user")
                .actualOutdoorMinutes(45).rushLoadScore((short) 20)
                .createdAt(startsAt).updatedAt(startsAt)
                .build());

        String response = mockMvc.perform(get("/summary/daily")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-16"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCount").value(1))
                .andExpect(jsonPath("$.totalOutdoorMinutes").value(45))
                .andExpect(jsonPath("$.outdoorSource").value("observed"))
                .andExpect(jsonPath("$.isViewed").value(false))
                .andReturn().getResponse().getContentAsString();
        String summaryId = JsonPath.read(response, "$.summaryId");

        mockMvc.perform(post("/summary/daily/" + summaryId + "/viewed")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isViewed").value(true));

        mockMvc.perform(get("/summary/daily")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-16"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isViewed").value(true));

        assertThat(productEventRepository.findAll())
                .anyMatch(pe -> userId.equals(pe.getUserId()) && "card_viewed".equals(pe.getEventName()));
    }

    @Test
    void 야외_이동_데이터가_전혀_없으면_observed라고_주장하지_않는다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);
        LocalDate date = LocalDate.of(2026, 8, 17);
        Instant startsAt = date.atTime(14, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();

        Event event = eventRepository.save(Event.builder()
                .userId(userId).sourceType("internal").startsAt(startsAt)
                .isAllDay(false).locationState("not_required").autoManageExcluded(false)
                .status("closed").createdAt(startsAt)
                .build());
        planRevisionRepository.save(PlanRevision.builder()
                .eventId(event.getEventId()).revisionNo(1)
                .prepStartAt(startsAt).recommendedDepartAt(startsAt).targetArriveAt(startsAt)
                .estimatedPrepMinutes(0).extraPrepMinutes(0).personalRoutineMinutes(0)
                .travelMinutes(0).trafficBufferMinutes(0).arrivalBufferMinutes(0)
                .feasible(true).reasons("[]").degraded("[]")
                .predictionConfidence("high").planStatus("active").calcVersion("test")
                .createdAt(startsAt)
                .build());

        mockMvc.perform(get("/summary/daily")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOutdoorMinutes").value(0))
                .andExpect(jsonPath("$.outdoorSource").value("estimated"))
                .andExpect(jsonPath("$.dwlScore").doesNotExist())
                .andExpect(jsonPath("$.dwlBand").value("unknown"));
    }

    @Test
    void 관리_제외_상태는_일일_요약에서_제외한다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);
        LocalDate date = LocalDate.of(2026, 8, 18);
        Instant startsAt = date.atTime(14, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();

        eventRepository.save(Event.builder()
                .userId(userId).sourceType("internal").startsAt(startsAt)
                .isAllDay(false).locationState("not_required").autoManageExcluded(false)
                .status("cancelled").createdAt(startsAt)
                .build());

        mockMvc.perform(get("/summary/daily")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-18"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SUMMARY_NOT_GENERATED"));
    }

    /**
     * 홈 wrap 카드(S-06)의 "정시 도착" 칸. 정시는 on_time만 세고, early는 분모에만
     * 들어간다 — 너무 일찍 도착한 것도 계획이 빗나간 것이다. unknown은 결과를 모르는
     * 것이므로 분모에서도 빠진다(주간 요약과 같은 정의).
     */
    @Test
    void 정시_도착은_on_time만_세고_early는_분모에만_들어간다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);
        LocalDate date = LocalDate.of(2026, 8, 23);

        arrivedEvent(userId, date, 10, "on_time");
        arrivedEvent(userId, date, 12, "early");
        arrivedEvent(userId, date, 14, "late");
        arrivedEvent(userId, date, 16, "unknown");

        mockMvc.perform(get("/summary/daily")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCount").value(4))
                .andExpect(jsonPath("$.onTimeCount").value(1))
                .andExpect(jsonPath("$.arrivalSampleCount").value(3));
    }

    @Test
    void 도착_결과를_하나도_모르면_정시_도착_표본이_0이다() throws Exception {
        String accessToken = signupAndLogin();
        UUID userId = extractUserId(accessToken);
        LocalDate date = LocalDate.of(2026, 8, 24);

        // 실행 기록이 아예 없는 일정. 0회를 "정시가 한 번도 없었다"로 읽으면 안 되므로
        // 표본 수로 데이터 부재를 구분한다.
        arrivedEvent(userId, date, 10, null);

        mockMvc.perform(get("/summary/daily")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-08-24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onTimeCount").value(0))
                .andExpect(jsonPath("$.arrivalSampleCount").value(0));
    }

    /** 일정 + 계획 + (선택) 도착 결과를 한 벌 만든다. arrivalResult가 null이면 실행 기록을 만들지 않는다. */
    private void arrivedEvent(UUID userId, LocalDate date, int hour, String arrivalResult) {
        Instant startsAt = date.atTime(hour, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        Event event = eventRepository.save(Event.builder()
                .userId(userId).sourceType("internal").startsAt(startsAt)
                .isAllDay(false).locationState("not_required").autoManageExcluded(false)
                .status("closed").createdAt(startsAt)
                .build());
        PlanRevision revision = planRevisionRepository.save(PlanRevision.builder()
                .eventId(event.getEventId()).revisionNo(1)
                .prepStartAt(startsAt).recommendedDepartAt(startsAt).targetArriveAt(startsAt)
                .estimatedPrepMinutes(0).extraPrepMinutes(0).personalRoutineMinutes(0)
                .travelMinutes(0).trafficBufferMinutes(0).arrivalBufferMinutes(0)
                .feasible(true).reasons("[]").degraded("[]")
                .predictionConfidence("high").planStatus("active").calcVersion("test")
                .createdAt(startsAt)
                .build());
        if (arrivalResult == null) {
            return;
        }
        eventExecutionRepository.save(EventExecution.builder()
                .eventId(event.getEventId()).finalPlanId(revision.getPlanId())
                .arrivalResult(arrivalResult).resultSource("user")
                .createdAt(startsAt).updatedAt(startsAt)
                .build());
    }

    @Test
    void 관리한_일정이_없으면_404() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(get("/summary/daily")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("date", "2026-01-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SUMMARY_NOT_GENERATED"));
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
