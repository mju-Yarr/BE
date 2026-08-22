package com.hq.backend.plan;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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

@SpringBootTest
@AutoConfigureMockMvc
class TodayPlanControllerTest {

    // 가입 기본 timezone. 카드 대상 구간이 이 zone의 하루 경계로 잘린다.
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 오늘_일정이_없으면_빈_ease_홈을_돌려준다() throws Exception {
        String token = signupAndLogin();

        mockMvc.perform(get("/plans/today").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(LocalDate.now(SEOUL).toString()))
                .andExpect(jsonPath("$.homeState").value("ease"))
                .andExpect(jsonPath("$.cards.length()").value(0))
                .andExpect(jsonPath("$.serverNow").exists());
    }

    @Test
    void 오늘_일정만_카드로_내려오고_내일_일정은_빠진다() throws Exception {
        String token = signupAndLogin();
        String todayEventId = createEvent(token, endOfToday(), "오늘 회의");
        createEvent(token, endOfToday().plusSeconds(3600), "내일 회의");

        mockMvc.perform(get("/plans/today").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(1))
                .andExpect(jsonPath("$.cards[0].event.eventId").value(todayEventId))
                .andExpect(jsonPath("$.cards[0].event.displayName").value("오늘 회의"))
                .andExpect(jsonPath("$.cards[0].plan").doesNotExist());
    }

    @Test
    void 시작_시각이_지난_일정은_rush_카드이고_홈도_rush다() throws Exception {
        String token = signupAndLogin();
        createEvent(token, startOfToday(), endOfToday(), "진행 중인 일정");

        mockMvc.perform(get("/plans/today").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(1))
                .andExpect(jsonPath("$.cards[0].state").value("rush"))
                .andExpect(jsonPath("$.homeState").value("rush"));
    }

    @Test
    void 취소한_일정은_오늘_카드에서_빠진다() throws Exception {
        String token = signupAndLogin();
        String eventId = createEvent(token, startOfToday(), "취소할 일정");
        mockMvc.perform(delete("/events/" + eventId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/plans/today").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(0))
                .andExpect(jsonPath("$.homeState").value("ease"));
    }

    @Test
    void 다른_사용자의_일정은_내_오늘_홈에_나오지_않는다() throws Exception {
        String ownerToken = signupAndLogin();
        createEvent(ownerToken, startOfToday(), "남의 일정");
        String otherToken = signupAndLogin();

        mockMvc.perform(get("/plans/today").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(0));
    }

    @Test
    void 인증_없이는_오늘_홈을_조회할_수_없다() throws Exception {
        mockMvc.perform(get("/plans/today")).andExpect(status().isUnauthorized());
    }

    private Instant startOfToday() {
        return LocalDate.now(SEOUL).atStartOfDay(SEOUL).toInstant();
    }

    private Instant endOfToday() {
        return LocalDate.now(SEOUL).atStartOfDay(SEOUL).plusDays(1).minusMinutes(1).toInstant();
    }

    private String createEvent(String token, Instant startsAt, String label) throws Exception {
        return createEvent(token, startsAt, null, label);
    }

    private String createEvent(String token, Instant startsAt, Instant endsAt, String label) throws Exception {
        String response = mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startsAt":"%s","endsAt":%s,"locationState":"NOT_REQUIRED","sourceType":"INTERNAL",
                                 "displayLabel":"%s"}
                                """.formatted(startsAt, endsAt == null ? "null" : "\"" + endsAt + "\"", label)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.eventId");
    }

    private String signupAndLogin() throws Exception {
        String email = "today-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"securePassword123\"}".formatted(email)))
                .andExpect(status().isCreated());
        String response = mockMvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"securePassword123\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }
}
