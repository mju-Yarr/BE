package com.hq.backend.bookmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RecentDestinationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecentDestinationRepository recentDestinationRepository;

    @Test
    void 목적지가_있는_일정을_만들면_최근_목적지로_기록된다() throws Exception {
        Account account = signupAndLogin();
        createEventWithDestination(account.accessToken(), "2026-08-20T14:00:00+09:00", "강남역");

        mockMvc.perform(get("/me/recent-destinations")
                        .header("Authorization", "Bearer " + account.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].placeName").value("강남역"))
                .andExpect(jsonPath("$[0].bookmarked").value(false));

        assertThat(recentDestination(account)).get()
                .satisfies(value -> assertThat(value.getUseCount()).isEqualTo(1));
    }

    @Test
    void 같은_좌표를_다시_쓰면_새_행_대신_사용_횟수가_올라간다() throws Exception {
        Account account = signupAndLogin();
        createEventWithDestination(account.accessToken(), "2026-08-20T14:00:00+09:00", "강남역");
        createEventWithDestination(account.accessToken(), "2026-08-21T14:00:00+09:00", "강남역 2번출구");

        mockMvc.perform(get("/me/recent-destinations")
                        .header("Authorization", "Bearer " + account.accessToken()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].placeName").value("강남역 2번출구"));

        assertThat(recentDestination(account)).get()
                .satisfies(value -> assertThat(value.getUseCount()).isEqualTo(2));
    }

    @Test
    void 같은_좌표의_북마크가_있으면_bookmarked로_표시된다() throws Exception {
        Account account = signupAndLogin();
        createEventWithDestination(account.accessToken(), "2026-08-20T14:00:00+09:00", "강남역");
        mockMvc.perform(post("/me/bookmarks")
                        .header("Authorization", "Bearer " + account.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeName\":\"강남역\",\"lat\":37.498,\"lng\":127.027}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/me/recent-destinations")
                        .header("Authorization", "Bearer " + account.accessToken()))
                .andExpect(jsonPath("$[0].bookmarked").value(true));
    }

    @Test
    void 최근_목적지는_사용자별로_분리되고_삭제할_수_있다() throws Exception {
        Account owner = signupAndLogin();
        createEventWithDestination(owner.accessToken(), "2026-08-20T14:00:00+09:00", "강남역");
        Account other = signupAndLogin();

        mockMvc.perform(get("/me/recent-destinations")
                        .header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(delete("/me/recent-destinations")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/me/recent-destinations")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    private java.util.Optional<RecentDestination> recentDestination(Account account) {
        return recentDestinationRepository.findByUserIdAndLatAndLng(
                account.userId(), new BigDecimal("37.498000"), new BigDecimal("127.027000"));
    }

    private void createEventWithDestination(String token, String startsAt, String destinationName) throws Exception {
        mockMvc.perform(post("/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startsAt":"%s","locationState":"NOT_REQUIRED","sourceType":"MAP_SEARCH",
                                 "destinationName":"%s","destinationAddress":"서울 강남구",
                                 "destinationLat":37.498,"destinationLng":127.027}
                                """.formatted(startsAt, destinationName)))
                .andExpect(status().isCreated());
    }

    private Account signupAndLogin() throws Exception {
        String email = "recent-" + UUID.randomUUID() + "@example.com";
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
