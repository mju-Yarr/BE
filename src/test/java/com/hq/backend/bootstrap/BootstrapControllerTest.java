package com.hq.backend.bootstrap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BootstrapControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 부트스트랩은_기본값과_빈_목록으로_200을_반환한다() throws Exception {
        String accessToken = signupAndLogin();

        mockMvc.perform(get("/me/bootstrap").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").exists())
                .andExpect(jsonPath("$.user.nickname").isNotEmpty())
                .andExpect(jsonPath("$.user.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.user.accountStatus").value("active"))
                .andExpect(jsonPath("$.settings.arrivalBufferMinutes").value(10))
                .andExpect(jsonPath("$.settings.notificationSensitivity").value("normal"))
                .andExpect(jsonPath("$.permissions").isEmpty())
                .andExpect(jsonPath("$.places").isEmpty())
                .andExpect(jsonPath("$.prepItems").isEmpty())
                .andExpect(jsonPath("$.gate.onboarding.currentStep").value("profile"))
                .andExpect(jsonPath("$.gate.onboarding.completed").value(false))
                .andExpect(jsonPath("$.todayPlan.homeState").value("ease"))
                .andExpect(jsonPath("$.todayPlan.cards").isEmpty())
                .andExpect(jsonPath("$.engineConfig.engineVer").value("2.1.0"));
    }

    @Test
    void 인증_없이_요청하면_401() throws Exception {
        mockMvc.perform(get("/me/bootstrap"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
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
