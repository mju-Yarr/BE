package com.hq.backend.onboarding;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class OnboardingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 가입_직후_온보딩은_첫_단계이며_완료_전이다() throws Exception {
        String token = signupAndLogin();

        mockMvc.perform(get("/me/onboarding").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value("profile"))
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.coachmarkSeen").value(false));
    }

    @Test
    void 진행_단계는_저장되고_지원하지_않는_단계는_422다() throws Exception {
        String token = signupAndLogin();

        patchStep(token, "places").andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value("places"));
        mockMvc.perform(get("/me/onboarding").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.currentStep").value("places"));

        patchStep(token, "unknown_step").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_ONBOARDING_STEP"));
        patchStep(token, "completed").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_ONBOARDING_STEP"));
    }

    @Test
    void 코치마크는_온보딩_완료_후에만_기록된다() throws Exception {
        String token = signupAndLogin();

        mockMvc.perform(post("/me/onboarding/coachmark-seen").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ONBOARDING_NOT_COMPLETED"));

        mockMvc.perform(post("/me/onboarding/complete").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value("completed"))
                .andExpect(jsonPath("$.completed").value(true));

        mockMvc.perform(post("/me/onboarding/coachmark-seen").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/me/onboarding").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.coachmarkSeen").value(true));
    }

    @Test
    void 완료_후에는_이전_단계로_되돌아가지_않는다() throws Exception {
        String token = signupAndLogin();
        mockMvc.perform(post("/me/onboarding/complete").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        patchStep(token, "places").andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value("completed"))
                .andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void 인증_없이는_온보딩_상태를_조회할_수_없다() throws Exception {
        mockMvc.perform(get("/me/onboarding")).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions patchStep(String token, String step) throws Exception {
        return mockMvc.perform(patch("/me/onboarding")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentStep\":\"%s\"}".formatted(step)));
    }

    private String signupAndLogin() throws Exception {
        String email = "onboarding-" + UUID.randomUUID() + "@example.com";
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
