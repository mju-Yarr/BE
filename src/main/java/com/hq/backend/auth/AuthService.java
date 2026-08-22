package com.hq.backend.auth;

import com.hq.backend.auth.dto.EmailVerificationConfirmResponse;
import com.hq.backend.auth.dto.EmailVerificationSendResponse;
import com.hq.backend.auth.dto.GoogleLoginRequest;
import com.hq.backend.auth.dto.GoogleUserInfoResponse;
import com.hq.backend.auth.dto.LoginRequest;
import com.hq.backend.auth.dto.SignupRequest;
import com.hq.backend.auth.dto.SignupResponse;
import com.hq.backend.auth.dto.TokenResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.consent.UserConsent;
import com.hq.backend.consent.UserConsentRepository;
import com.hq.backend.onboarding.OnboardingService;
import com.hq.backend.onboarding.UserOnboardingRepository;
import com.hq.backend.onboarding.dto.OnboardingProgressResponse;
import com.hq.backend.pushdevice.PushDeviceRepository;
import com.hq.backend.setting.UserSetting;
import com.hq.backend.setting.UserSettingRepository;
import com.hq.backend.user.User;
import com.hq.backend.user.UserCredential;
import com.hq.backend.user.UserCredentialRepository;
import com.hq.backend.user.UserIdentity;
import com.hq.backend.user.UserIdentityRepository;
import com.hq.backend.user.UserRepository;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

// ERD v3 전환(chore/be-schema-core, #61)에 맞춰 provider/providerUid/passwordHash가
// USER_IDENTITY/USER_CREDENTIAL로 옮겨간 것을 반영한 최소 수정. 로직(구글 토큰 검증,
// JWT 발급, 이메일 인증)은 기존 그대로이고 데이터 접근 경로만 새 테이블로 바꿨다.
// 만 14세 확인(age_confirmed_at)은 Ensom 범위에 없어 뺐다 — TODO(박찬): 이메일 인증
// 토큰 발급(AUTH_TOKEN), USER_IDENTITY 기반 계정 연결 정책 등 실제 Ensom 인증 플로우는
// 아직 반영 안 됨.
@Service
public class AuthService {

    private static final short LOGIN_FAIL_LOCK_THRESHOLD = 5;
    private static final long LOGIN_LOCK_MINUTES = 15;
    // API 명세 §2.8·클라이언트 동의 화면과 같은 목록. marketing은 선택이라 빠진다.
    private static final List<String> REQUIRED_CONSENT_TYPES = List.of("terms", "privacy", "location");

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PushDeviceRepository pushDeviceRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RestClient restClient;
    private final TransactionTemplate transactionTemplate;
    private final EmailVerificationService emailVerificationService;
    private final UserConsentRepository userConsentRepository;
    private final UserSettingRepository userSettingRepository;
    private final UserOnboardingRepository userOnboardingRepository;

    @Autowired
    public AuthService(UserRepository userRepository, UserIdentityRepository userIdentityRepository,
            UserCredentialRepository userCredentialRepository, RefreshTokenRepository refreshTokenRepository,
            PushDeviceRepository pushDeviceRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
            RestClient restClient, TransactionTemplate transactionTemplate,
            EmailVerificationService emailVerificationService, UserConsentRepository userConsentRepository,
            UserSettingRepository userSettingRepository, UserOnboardingRepository userOnboardingRepository) {
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.userCredentialRepository = userCredentialRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.pushDeviceRepository = pushDeviceRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.restClient = restClient;
        this.transactionTemplate = transactionTemplate;
        this.emailVerificationService = emailVerificationService;
        this.userConsentRepository = userConsentRepository;
        this.userSettingRepository = userSettingRepository;
        this.userOnboardingRepository = userOnboardingRepository;
    }

    /** Source-compatible constructor retained for existing isolated unit tests. */
    AuthService(UserRepository userRepository, UserIdentityRepository userIdentityRepository,
            UserCredentialRepository userCredentialRepository, RefreshTokenRepository refreshTokenRepository,
            PushDeviceRepository pushDeviceRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
            RestClient restClient, TransactionTemplate transactionTemplate,
            EmailVerificationService emailVerificationService, UserConsentRepository userConsentRepository) {
        this(userRepository, userIdentityRepository, userCredentialRepository, refreshTokenRepository,
                pushDeviceRepository, passwordEncoder, jwtService, restClient, transactionTemplate,
                emailVerificationService, userConsentRepository, null, null);
    }

    @Value("${app.consent.policy-version}")
    private String consentPolicyVersion;

    @Value("${oauth.google.token-info-url}")
    private String googleTokenInfoUrl;

    @Value("${oauth.google.client-id}")
    private String googleClientId;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        boolean ticketFlow = request.verificationTicket() != null && !request.verificationTicket().isBlank();
        boolean atomicFieldsPresent = blankToNull(request.name()) != null || blankToNull(request.nickname()) != null
                || blankToNull(request.timezone()) != null || request.installationId() != null || request.consents() != null;
        validatePassword(request.password());
        if (atomicFieldsPresent || ticketFlow) {
            validateAtomicSignup(request);
            if (!ticketFlow) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VERIFICATION_TICKET_REQUIRED",
                        "확장 가입 정보에는 이메일 인증 티켓이 필요합니다.");
            }
        }
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "이미 가입된 이메일입니다.");
        }
        if (ticketFlow) {
            emailVerificationService.consumeSignupTicket(email, request.verificationTicket());
        }

        Instant now = Instant.now();
        String requestedNickname = blankToNull(request.nickname());
        String nickname = requestedNickname == null ? availableDefaultNickname(email) : requestedNickname.trim();
        User user;
        try {
            user = userRepository.saveAndFlush(User.builder()
                    .fullName(blankToNull(request.name()))
                    .email(email)
                    .nickname(nickname)
                    .timezone(normalizeTimezone(request.timezone()))
                    .registrationInstallationId(request.installationId())
                    .createdAt(now)
                    .emailVerifiedAt(ticketFlow ? now : null)
                    .accountStatus("active")
                    .build());
        } catch (DataIntegrityViolationException conflict) {
            if (requestedNickname != null || isConstraint(conflict, "uq_users_nickname_normalized")) {
                throw new ApiException(HttpStatus.CONFLICT, "NICKNAME_EXISTS", "이미 사용 중인 닉네임입니다.");
            }
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "이미 가입된 이메일입니다.");
        }

        userIdentityRepository.save(UserIdentity.builder()
                .userId(user.getUserId()).provider("email").providerUid(email).linkedAt(now).build());
        userCredentialRepository.save(UserCredential.builder()
                .userId(user.getUserId()).passwordHash(passwordEncoder.encode(request.password()))
                .passwordAlgo("argon2id").passwordUpdatedAt(now).failedAttempts((short) 0).build());
        persistInitialState(user.getUserId(), now);
        if (ticketFlow) recordSignupConsents(user.getUserId(), request.consents(), now);

        boolean emailVerificationRequired = !ticketFlow && emailVerificationService.isEnabled();
        if (emailVerificationRequired) emailVerificationService.issueAndSend(user);
        else if (!ticketFlow) user.setEmailVerifiedAt(now);
        return new SignupResponse(user.getUserId(), user.getEmail(), !emailVerificationRequired, emailVerificationRequired);
    }

    public EmailVerificationSendResponse sendVerificationCode(String email) {
        return sendVerificationCode(email, "unknown");
    }

    public EmailVerificationSendResponse sendVerificationCode(String email, String clientAddress) {
        return emailVerificationService.sendCode(email, clientAddress);
    }

    public EmailVerificationConfirmResponse confirmVerificationCode(String email, String code) {
        return confirmVerificationCode(email, code, "unknown");
    }

    public EmailVerificationConfirmResponse confirmVerificationCode(String email, String code, String clientAddress) {
        return emailVerificationService.confirmCode(email, code, clientAddress);
    }

    private void validateAtomicSignup(SignupRequest request) {
        if (blankToNull(request.name()) == null || blankToNull(request.nickname()) == null
                || blankToNull(request.timezone()) == null || request.installationId() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR",
                    "name, nickname, timezone, installationId는 필수입니다.");
        }
        if (userRepository.existsByNicknameIgnoreCase(request.nickname().trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "NICKNAME_EXISTS", "이미 사용 중인 닉네임입니다.");
        }
        Map<String, Boolean> consents = request.consents() == null ? Map.of() : request.consents();
        if (REQUIRED_CONSENT_TYPES.stream().anyMatch(type -> !Boolean.TRUE.equals(consents.get(type)))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REQUIRED_CONSENT_MISSING",
                    "필수 약관 동의가 필요합니다.");
        }
        normalizeTimezone(request.timezone());
    }

    private void validatePassword(String password) {
        if (!password.matches("^(?=.*[A-Za-z])(?=.*\\d).{8,}$")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PASSWORD",
                    "비밀번호는 8자 이상이며 영문과 숫자를 포함해야 합니다.");
        }
    }

    private void recordSignupConsents(UUID userId, Map<String, Boolean> consents, Instant now) {
        if (userConsentRepository == null || consents == null) return;
        consents.forEach((type, agreed) -> {
            if (!List.of("terms", "privacy", "location", "marketing").contains(type)) return;
            userConsentRepository.save(UserConsent.builder().userId(userId).consentType(type)
                    .policyVersion(consentPolicyVersion).action(Boolean.TRUE.equals(agreed) ? "agreed" : "revoked")
                    .isRequired(REQUIRED_CONSENT_TYPES.contains(type)).idempotencyKey(UUID.randomUUID())
                    .recordedAt(now).build());
        });
    }

    private void persistInitialState(UUID userId, Instant now) {
        if (userSettingRepository != null) {
            userSettingRepository.save(UserSetting.builder().userId(userId).initialPrepMinutes(null)
                    .arrivalBufferMinutes(10).notificationSensitivity("normal")
                    .personalizationEnabled(true).autoManageEnabled(true).wellnessEventEnabled(false)
                    .lockscreenHideSensitive(true).updatedAt(now).build());
        }
        if (userOnboardingRepository != null) userOnboardingRepository.save(OnboardingService.initial(userId, now));
    }

    private String normalizeTimezone(String timezone) {
        String value = blankToNull(timezone) == null ? "Asia/Seoul" : timezone.trim();
        try { ZoneId.of(value); return value; }
        catch (Exception ignored) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TIMEZONE", "유효하지 않은 timezone입니다.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // users.nickname은 not null이지만 가입 요청에 닉네임 입력을 받지 않으므로 임시값을 채운다.
    // 닉네임 설정 기능이 생기면 그때 덮어쓰면 된다.
    public void verifyEmail(String token) {
        emailVerificationService.verify(token);
    }

    public void resendEmailVerification(String email) {
        emailVerificationService.resend(email);
    }

    private String availableDefaultNickname(String email) {
        String base = email.substring(0, email.indexOf('@'));
        if (!userRepository.existsByNicknameIgnoreCase(base)) return base;
        return base + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private boolean isConstraint(DataIntegrityViolationException conflict, String constraintName) {
        Throwable current = conflict;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(constraintName)) return true;
            current = current.getCause();
        }
        return false;
    }

    // TRD §10.2·부록A: 연속 5회 실패 시 15분 잠금. IP 단위 제한은 아직 없다(계정 단위만).
    // noRollbackFor: 실패 응답(ApiException)을 던지더라도 failedAttempts/lockedUntil 증가는
    // 커밋돼야 한다 — 기본 롤백 규칙대로면 실패를 보고하는 예외가 그 실패 카운트 자체를 지운다.
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."));

        UserCredential credential = userCredentialRepository.findById(user.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."));

        if (credential.getLockedUntil() != null && credential.getLockedUntil().isAfter(Instant.now())) {
            long retryAfterSec = Duration.between(Instant.now(), credential.getLockedUntil()).getSeconds();
            throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                    "연속 로그인 실패로 계정이 잠겼습니다. " + retryAfterSec + "초 후 다시 시도해주세요.");
        }

        if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            registerFailedAttempt(credential);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        if (emailVerificationService.isEnabled() && user.getEmailVerifiedAt() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_VERIFICATION_REQUIRED",
                    "이메일 인증을 완료한 뒤 로그인할 수 있습니다.");
        }

        if (credential.getFailedAttempts() > 0) {
            credential.setFailedAttempts((short) 0);
        }

        // email identity가 해제(revoked)됐으면 로그인 차단
        if (userIdentityRepository.findByUserIdAndProviderAndRevokedAtIsNull(user.getUserId(), "email").isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        return issueTokens(user, false);
    }

    private void registerFailedAttempt(UserCredential credential) {
        short attempts = (short) (credential.getFailedAttempts() + 1);
        credential.setFailedAttempts(attempts);
        if (attempts >= LOGIN_FAIL_LOCK_THRESHOLD) {
            credential.setLockedUntil(Instant.now().plus(LOGIN_LOCK_MINUTES, ChronoUnit.MINUTES));
        }
    }

    // 구글 토큰 검증(외부 호출)을 트랜잭션 밖에서 먼저 끝내고, DB 쓰기가 필요한 신규 유저
    // 생성만 createGoogleUser() 안에서 TransactionTemplate으로 짧게 감싼다 — 네트워크
    // 호출 동안 DB 커넥션을 붙잡지 않기 위해서(FCM 전송을 트랜잭션 밖에 두는 것과 같은 원칙).
    public TokenResponse loginWithGoogle(GoogleLoginRequest request) {
        // id_token을 문자열로 이어붙이면 {}가 든 값이 URI 템플릿 변수로 해석돼 500이 난다.
        // encode()로 쿼리 파라미터를 인코딩한 URI를 넘겨 템플릿 확장을 우회한다.
        URI uri = UriComponentsBuilder.fromUriString(googleTokenInfoUrl)
                .queryParam("id_token", request.idToken())
                .encode()
                .build()
                .toUri();

        GoogleUserInfoResponse info;
        try {
            info = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(GoogleUserInfoResponse.class);
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN", "구글 토큰이 유효하지 않습니다.");
        }

        if (info == null || !googleClientId.equals(info.aud())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN", "유효하지 않은 구글 토큰입니다.");
        }

        UserIdentity googleIdentity = userIdentityRepository
                .findByProviderAndProviderUid("google", info.sub())
                .orElse(null);
        if (googleIdentity != null && googleIdentity.getRevokedAt() != null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "로그인할 수 없는 인증 정보입니다.");
        }

        User user = googleIdentity == null
                ? null
                : userRepository.findById(googleIdentity.getUserId())
                        .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "USER_NOT_FOUND", "계정을 찾을 수 없습니다."));

        boolean isNew = (user == null);
        if (isNew) {
            user = createGoogleUser(info);
        }

        return issueTokens(user, isNew);
    }

    private User createGoogleUser(GoogleUserInfoResponse info) {
        try {
            return transactionTemplate.execute(status -> {
                Instant now = Instant.now();
                User user = userRepository.save(User.builder()
                        .email(info.email())
                        .nickname(availableDefaultNickname(info.email()))
                        .timezone("Asia/Seoul")
                        .createdAt(now)
                        .emailVerifiedAt(now)
                        .accountStatus("active")
                        .build());

                userIdentityRepository.save(UserIdentity.builder()
                        .userId(user.getUserId())
                        .provider("google")
                        .providerUid(info.sub())
                        .linkedAt(now)
                        .build());
                persistInitialState(user.getUserId(), now);

                return user;
            });
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "이미 다른 방식으로 가입된 이메일입니다.");
        }
    }

    private TokenResponse issueTokens(User user, boolean isNew) {
        String accessToken = jwtService.generateAccessToken(user.getUserId());
        String refreshToken = jwtService.generateRefreshToken(user.getUserId());

        // refresh 토큰 해시를 DB에 저장 (회전·폐기 지원)
        String tokenHash = hashToken(refreshToken);
        Instant expiresAt = Instant.now().plusMillis(jwtService.getRefreshTokenExpirationMs());
        refreshTokenRepository.save(RefreshToken.create(user.getUserId(), tokenHash, expiresAt));

        return new TokenResponse(
                accessToken,
                refreshToken,
                jwtService.getAccessTokenExpirationSeconds(),
                new TokenResponse.UserInfo(
                        user.getUserId().toString(),
                        user.getNickname(),
                        user.getTimezone(),
                        isNew),
                consentRequiredFor(user.getUserId()),
                onboardingFor(user.getUserId()));
    }

    private OnboardingProgressResponse onboardingFor(UUID userId) {
        if (userOnboardingRepository == null) {
            return new OnboardingProgressResponse(OnboardingService.FIRST_STEP, false, null, false);
        }
        return userOnboardingRepository.findById(userId)
                .map(OnboardingProgressResponse::from)
                .orElseGet(() -> new OnboardingProgressResponse(OnboardingService.FIRST_STEP, false, null, false));
    }

    // 필수 약관별로 가장 최근 기록 하나만 본다 — action이 revoked거나 policyVersion이
    // 현재 버전보다 낮으면 재동의 대상이다. 3건짜리 조회라 배치 쿼리를 따로 만들지 않았다.
    private List<String> consentRequiredFor(UUID userId) {
        return REQUIRED_CONSENT_TYPES.stream()
                .filter(type -> userConsentRepository
                        .findFirstByUserIdAndConsentTypeOrderByRecordedAtDescConsentEventIdDesc(userId, type)
                        .filter(consent -> "agreed".equals(consent.getAction())
                                && consentPolicyVersion.equals(consent.getPolicyVersion()))
                        .isEmpty())
                .toList();
    }

    /**
     * Refresh 토큰으로 새 토큰 쌍 발급 (토큰 회전).
     * 조건부 UPDATE로 동시 요청 시 하나만 성공하도록 보장한다.
     */
    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        UUID userId = jwtService.getUserIdFromRefreshToken(rawRefreshToken);

        String tokenHash = hashToken(rawRefreshToken);

        // 원자적 소비: revoked_at IS NULL AND expires_at > now() 인 경우에만 revoke
        int consumed = refreshTokenRepository.revokeByTokenHash(tokenHash);
        if (consumed == 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "만료되었거나 이미 사용된 토큰입니다.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "사용자를 찾을 수 없습니다."));

        return issueTokens(user, false);
    }

    /**
     * 로그아웃 — 해당 사용자의 모든 활성 refresh 토큰 폐기 + push device 비활성화.
     */
    @Transactional
    public void logout(UUID userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
        pushDeviceRepository.revokeAllByUserId(userId);
    }

    private String hashToken(String token) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
