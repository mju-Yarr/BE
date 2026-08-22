package com.hq.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.hq.backend.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "app.email-verification.enabled=true")
@AutoConfigureMockMvc
@Import(AuthControllerTest.EmailVerificationTestConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private CapturingVerificationEmailSender verificationEmailSender;

    @TestConfiguration
    static class EmailVerificationTestConfig {
        @Bean
        @Primary
        CapturingVerificationEmailSender verificationEmailSender() {
            return new CapturingVerificationEmailSender();
        }
    }

    static class CapturingVerificationEmailSender implements VerificationEmailSender {
        private final ConcurrentHashMap<String, String> codes = new ConcurrentHashMap<>();

        @Override public boolean isAvailable() { return true; }
        @Override public void sendVerificationLink(String recipientEmail, String verificationLink) { }
        @Override public void sendVerificationCode(String recipientEmail, String code) { codes.put(recipientEmail, code); }
        String codeFor(String email) { return codes.get(email); }
    }

    @Test
    void 이메일_인증_token_기본_TTL은_제품_정책대로_30분이다() {
        assertThat(ReflectionTestUtils.getField(emailVerificationService, "tokenTtlMinutes"))
                .isEqualTo(30L);
    }

    @Test
    void 확장_가입_정보가_있으면_인증_ticket을_생략할_수_없다() throws Exception {
        String email = "atomic-missing-ticket-" + UUID.randomUUID() + "@example.com";
        String body = """
                {"email":"%s","password":"securePassword123","name":"홍길동","nickname":"atomic_%s",
                 "timezone":"Asia/Seoul","installationId":"%s",
                 "consents":{"terms":true,"privacy":true,"location":true}}
                """.formatted(email, UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());

        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_TICKET_REQUIRED"));
    }

    @Test
    void 레거시_링크_가입도_공통_비밀번호_정책을_우회할_수_없다() throws Exception {
        String body = """
                {"email":"weak-%s@example.com","password":"abcdefgh"}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_PASSWORD"));
    }

    @Test
    void 코드_확인_ticket으로_atomic_signup하고_ticket은_재사용할_수_없다() throws Exception {
        String email = "atomic-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/auth/email/verification/send")
                        .header("X-Real-IP", "198.51.100.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());

        String code = verificationEmailSender.codeFor(email);
        MvcResult confirmResult = mockMvc.perform(post("/auth/email/verification/confirm")
                        .header("X-Real-IP", "198.51.100.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String ticket = JsonPath.read(confirmResult.getResponse().getContentAsString(), "$.verificationTicket");
        String nickname = "atomic_" + UUID.randomUUID().toString().substring(0, 8);
        String signupBody = """
                {"email":"%s","password":"securePassword123","name":"홍길동","nickname":"%s",
                 "timezone":"Asia/Seoul","installationId":"%s","verificationTicket":"%s",
                 "consents":{"terms":true,"privacy":true,"location":true,"marketing":false}}
                """.formatted(email, nickname, UUID.randomUUID(), ticket);

        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerified").value(true));
        assertThatThrownBy(() -> emailVerificationService.consumeSignupTicket(email, ticket))
                .isInstanceOf(com.hq.backend.common.exception.ApiException.class)
                .extracting("code").isEqualTo("INVALID_VERIFICATION_TICKET");
    }

    @Test
    void 인증_발송은_email_시간창_quota를_초과하면_429를_반환한다() throws Exception {
        String email = "quota-" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"" + email + "\"}";
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/auth/email/verification/send")
                            .header("X-Real-IP", "198.51.100." + (20 + i))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(post("/auth/email/verification/send")
                        .header("X-Real-IP", "198.51.100.30")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_RATE_LIMITED"));
    }

    @Test
    void signupThenLoginIssuesToken() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);

        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email));
        markEmailVerified(email);

        String loginBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);

        mockMvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.userId").exists())
                .andExpect(jsonPath("$.user.nickname").value(email.substring(0, email.indexOf('@'))))
                .andExpect(jsonPath("$.user.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.user.isNew").value(false));
    }

    @Test
    void signupDuplicateEmailIsRejected() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String body = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);

        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_EXISTS"));
    }

    @Test
    void 연속_5회_로그인_실패하면_계정이_잠긴다() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());

        String wrongLoginBody = """
                {"email":"%s","password":"wrongPassword123"}
                """.formatted(email);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON).content(wrongLoginBody))
                    .andExpect(status().isUnauthorized());
        }

        String correctLoginBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON).content(correctLoginBody))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void 로그인_직후_refresh하면_새_refresh_token을_발급한다() throws Exception {
        String refreshToken = signupAndLoginForRefresh();

        MvcResult result = refresh(refreshToken).andExpect(status().isOk()).andReturn();
        String response = result.getResponse().getContentAsString();
        String rotatedRefreshToken = JsonPath.read(response, "$.refreshToken");

        assertThat(rotatedRefreshToken).isNotEqualTo(refreshToken);
        assertThat(JsonPath.read(response, "$.user.userId").toString()).isNotBlank();
        assertThat((Boolean) JsonPath.read(response, "$.user.isNew")).isFalse();
    }

    @Test
    void 같은_refresh_token을_동시에_요청하면_하나만_성공한다() throws Exception {
        String refreshToken = signupAndLoginForRefresh();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startBarrier = new CyclicBarrier(2);

        try {
            Future<Integer> first = executor.submit(() -> {
                startBarrier.await();
                return refresh(refreshToken).andReturn().getResponse().getStatus();
            });
            Future<Integer> second = executor.submit(() -> {
                startBarrier.await();
                return refresh(refreshToken).andReturn().getResponse().getStatus();
            });

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 401);
        } finally {
            executor.shutdownNow();
        }
    }

    private String signupAndLoginForRefresh() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody))
                .andExpect(status().isCreated());
        markEmailVerified(email);

        String loginBody = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        MvcResult loginResult = mockMvc.perform(post("/auth/email/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(loginResult.getResponse().getContentAsString(), "$.refreshToken");
    }

    @Test
    void 미인증_이메일은_올바른_비밀번호여도_로그인할_수_없다() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String body = """
                {"email":"%s","password":"securePassword123"}
                """.formatted(email);
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"));
    }

    private void markEmailVerified(String email) {
        var user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
        String body = """
                {"refreshToken":"%s"}
                """.formatted(refreshToken);
        return mockMvc.perform(post("/auth/refresh")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
