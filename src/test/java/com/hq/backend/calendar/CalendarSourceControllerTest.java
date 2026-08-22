package com.hq.backend.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CalendarSourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CalendarConnectionRepository calendarConnectionRepository;

    @Autowired
    private CalendarSourceRepository calendarSourceRepository;

    @Test
    void 연결_목록은_연결마다_소스를_함께_돌려준다() throws Exception {
        Account account = signupAndLogin();
        UUID connectionId = connect(account.userId());
        UUID writable = source(connectionId, "primary", "내 캘린더", true, true);
        source(connectionId, "holiday", "공휴일", false, false);

        mockMvc.perform(get("/calendar/connections").header("Authorization", "Bearer " + account.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].calendarConnectionId").value(connectionId.toString()))
                .andExpect(jsonPath("$[0].provider").value("google"))
                .andExpect(jsonPath("$[0].sources.length()").value(2))
                .andExpect(jsonPath("$[0].sources[0].calendarSourceId").value(writable.toString()))
                .andExpect(jsonPath("$[0].sources[0].defaultSource").value(true))
                .andExpect(jsonPath("$[0].sources[0].writable").value(true));
    }

    @Test
    void 다른_사용자의_연결_소스는_보이지도_수정되지도_않는다() throws Exception {
        Account owner = signupAndLogin();
        UUID sourceId = source(connect(owner.userId()), "primary", "내 캘린더", true, true);
        Account other = signupAndLogin();

        mockMvc.perform(get("/calendar/connections").header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(patch("/calendar/sources/" + sourceId)
                        .header("Authorization", "Bearer " + other.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncEnabled\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CALENDAR_SOURCE_NOT_FOUND"));

        mockMvc.perform(post("/calendar/sources/" + sourceId + "/default")
                        .header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CALENDAR_SOURCE_NOT_FOUND"));
    }

    @Test
    void 소스_동기화_여부는_PATCH로_바뀐다() throws Exception {
        Account account = signupAndLogin();
        UUID sourceId = source(connect(account.userId()), "primary", "내 캘린더", true, true);

        mockMvc.perform(patch("/calendar/sources/" + sourceId)
                        .header("Authorization", "Bearer " + account.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncEnabled").value(false));

        assertThat(calendarSourceRepository.findById(sourceId)).get()
                .satisfies(value -> assertThat(value.isSyncEnabled()).isFalse());
    }

    @Test
    void 기본_기록_캘린더는_쓰기_가능한_소스만_될_수_있다() throws Exception {
        Account account = signupAndLogin();
        UUID connectionId = connect(account.userId());
        UUID writable = source(connectionId, "primary", "내 캘린더", true, true);
        UUID readOnly = source(connectionId, "holiday", "공휴일", false, false);

        mockMvc.perform(post("/calendar/sources/" + readOnly + "/default")
                        .header("Authorization", "Bearer " + account.accessToken()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CALENDAR_SOURCE_NOT_WRITABLE"));

        assertThat(calendarSourceRepository.findById(writable)).get()
                .satisfies(value -> assertThat(value.isDefault()).isTrue());
    }

    @Test
    void 쓰기_불가_소스로는_일정_기록_대상을_지정할_수_없다() throws Exception {
        Account account = signupAndLogin();
        UUID readOnly = source(connect(account.userId()), "holiday", "공휴일", false, false);

        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + account.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startsAt":"2026-08-20T14:00:00+09:00","locationState":"NOT_REQUIRED",
                                 "sourceType":"INTERNAL","displayLabel":"회의","writeToCalendarSourceId":"%s"}
                                """.formatted(readOnly)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CALENDAR_SOURCE_NOT_WRITABLE"));
    }

    private UUID connect(UUID userId) {
        return calendarConnectionRepository.saveAndFlush(CalendarConnection.builder()
                .userId(userId).provider("google")
                .externalAccountId("calendar-" + UUID.randomUUID() + "@example.com")
                .connectedAt(Instant.now()).build()).getCalendarConnectionId();
    }

    private UUID source(UUID connectionId, String externalId, String displayName,
            boolean writable, boolean isDefault) {
        return calendarSourceRepository.saveAndFlush(CalendarSource.builder()
                .calendarConnectionId(connectionId).externalCalendarId(externalId).displayName(displayName)
                .isWritable(writable).isDefault(isDefault).syncEnabled(true).build()).getCalendarSourceId();
    }

    private Account signupAndLogin() throws Exception {
        String email = "calendar-source-" + UUID.randomUUID() + "@example.com";
        String signup = mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"securePassword123\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String login = mockMvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"securePassword123\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Account(UUID.fromString(JsonPath.read(signup, "$.id").toString()),
                JsonPath.read(login, "$.accessToken"));
    }

    private record Account(UUID userId, String accessToken) {}
}
