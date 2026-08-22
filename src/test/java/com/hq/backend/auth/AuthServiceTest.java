package com.hq.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hq.backend.auth.dto.GoogleLoginRequest;
import com.hq.backend.auth.dto.GoogleUserInfoResponse;
import com.hq.backend.auth.dto.SignupRequest;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.user.User;
import com.hq.backend.user.UserCredentialRepository;
import com.hq.backend.user.UserIdentity;
import com.hq.backend.user.UserIdentityRepository;
import com.hq.backend.user.UserRepository;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock private RestClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @Mock private UserRepository userRepository;
    @Mock private UserIdentityRepository userIdentityRepository;
    @Mock private UserCredentialRepository userCredentialRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private com.hq.backend.pushdevice.PushDeviceRepository pushDeviceRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private EmailVerificationService emailVerificationService;
    @Mock private com.hq.backend.consent.UserConsentRepository userConsentRepository;

    private AuthService authService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        authService = new AuthService(
                userRepository, userIdentityRepository, userCredentialRepository, refreshTokenRepository, pushDeviceRepository, passwordEncoder, jwtService,
                restClient, transactionTemplate, emailVerificationService, userConsentRepository);
        ReflectionTestUtils.setField(authService, "consentPolicyVersion", "v1");
        ReflectionTestUtils.setField(authService, "googleTokenInfoUrl", "https://oauth2.googleapis.com/tokeninfo");
        ReflectionTestUtils.setField(authService, "googleClientId", "ensom-client-id");
    }

    @Test
    void 이메일_인증이_비활성화된_환경에서는_가입_즉시_검증처리하고_메일을_보내지_않는다() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsByEmail("local@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "userId", userId);
            return user;
        });
        when(passwordEncoder.encode("securePassword123")).thenReturn("password-hash");
        when(emailVerificationService.isEnabled()).thenReturn(false);

        var response = authService.signup(new SignupRequest("local@example.com", "securePassword123"));

        assertThat(response.emailVerified()).isTrue();
        assertThat(response.verificationSent()).isFalse();
        verify(emailVerificationService, never()).issueAndSend(any(User.class));
    }

    @Test
    void 이메일_인증이_활성화된_환경에서는_인증_메일을_발송한다() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsByEmail("verify@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "userId", userId);
            return user;
        });
        when(passwordEncoder.encode("securePassword123")).thenReturn("password-hash");
        when(emailVerificationService.isEnabled()).thenReturn(true);

        var response = authService.signup(new SignupRequest("verify@example.com", "securePassword123"));

        assertThat(response.emailVerified()).isFalse();
        assertThat(response.verificationSent()).isTrue();
        verify(emailVerificationService).issueAndSend(any(User.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 신규_구글_유저는_로그인시_가입되고_토큰을_발급받는다() {
        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        GoogleUserInfoResponse info = new GoogleUserInfoResponse("google-sub-1", "new@example.com", "ensom-client-id");
        when(responseSpec.body(GoogleUserInfoResponse.class)).thenReturn(info);
        when(userIdentityRepository.findByProviderAndProviderUid("google", "google-sub-1")).thenReturn(Optional.empty());
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<User> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        UUID userId = UUID.randomUUID();
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> {
                    User user = invocation.getArgument(0);
                    ReflectionTestUtils.setField(user, "userId", userId);
                    return user;
                });
        when(userIdentityRepository.save(any(UserIdentity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateAccessToken(userId)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(userId)).thenReturn("refresh-token");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(3600L);

        var result = authService.loginWithGoogle(new GoogleLoginRequest("id-token"));

        assertThat(result.accessToken()).isEqualTo("access-token");
    }

    @Test
    void aud가_설정된_클라이언트id와_다르면_거부한다() {
        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        GoogleUserInfoResponse info = new GoogleUserInfoResponse("google-sub-2", "other@example.com", "other-app-client-id");
        when(responseSpec.body(GoogleUserInfoResponse.class)).thenReturn(info);

        assertThatThrownBy(() -> authService.loginWithGoogle(new GoogleLoginRequest("id-token")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_GOOGLE_TOKEN");
    }
}
