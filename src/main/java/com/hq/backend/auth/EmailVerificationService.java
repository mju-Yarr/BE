package com.hq.backend.auth;

import com.hq.backend.auth.dto.EmailVerificationConfirmResponse;
import com.hq.backend.auth.dto.EmailVerificationSendResponse;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.user.User;
import com.hq.backend.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final short MAX_CODE_ATTEMPTS = 5;
    private static final int SEND_EMAIL_HOURLY_LIMIT = 5;
    private static final int SEND_EMAIL_DAILY_LIMIT = 20;
    private static final int SEND_IP_HOURLY_LIMIT = 20;
    private static final int SEND_IP_DAILY_LIMIT = 100;
    private static final int CONFIRM_EMAIL_HOURLY_LIMIT = 30;
    private static final int CONFIRM_EMAIL_DAILY_LIMIT = 100;
    private static final int CONFIRM_IP_HOURLY_LIMIT = 100;
    private static final int CONFIRM_IP_DAILY_LIMIT = 500;

    private final EmailVerificationTokenRepository tokenRepository;
    private final EmailVerificationChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final VerificationEmailSender verificationEmailSender;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.email-verification.enabled:true}") private boolean emailVerificationEnabled;
    @Value("${app.email-verification.token-ttl-minutes:30}") private long tokenTtlMinutes;
    @Value("${app.email-verification.resend-cooldown-seconds:60}") private long resendCooldownSeconds;
    @Value("${app.email-verification.base-url}") private String verificationBaseUrl;

    @Autowired
    public EmailVerificationService(EmailVerificationTokenRepository tokenRepository,
            EmailVerificationChallengeRepository challengeRepository, UserRepository userRepository,
            VerificationEmailSender verificationEmailSender, JdbcTemplate jdbcTemplate) {
        this.tokenRepository = tokenRepository;
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.verificationEmailSender = verificationEmailSender;
        this.jdbcTemplate = jdbcTemplate;
    }

    public EmailVerificationService(EmailVerificationTokenRepository tokenRepository,
            EmailVerificationChallengeRepository challengeRepository, UserRepository userRepository,
            VerificationEmailSender verificationEmailSender) {
        this(tokenRepository, challengeRepository, userRepository, verificationEmailSender, null);
    }

    public EmailVerificationService(EmailVerificationTokenRepository tokenRepository,
            UserRepository userRepository, VerificationEmailSender verificationEmailSender) {
        this(tokenRepository, null, userRepository, verificationEmailSender, null);
    }

    public boolean isEnabled() { return emailVerificationEnabled; }

    public EmailVerificationSendResponse sendCode(String rawEmail) {
        return sendCode(rawEmail, "unknown");
    }

    @Transactional
    public EmailVerificationSendResponse sendCode(String rawEmail, String clientAddress) {
        requireAvailable();
        String email = normalizeEmail(rawEmail);
        enforceQuota("send", email, clientAddress,
                SEND_EMAIL_HOURLY_LIMIT, SEND_EMAIL_DAILY_LIMIT, SEND_IP_HOURLY_LIMIT, SEND_IP_DAILY_LIMIT);
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "이미 가입된 이메일입니다.");
        }
        Instant now = Instant.now();
        EmailVerificationChallenge latest = challengeRepository
                .findFirstByEmailIgnoreCaseOrderByCreatedAtDesc(email).orElse(null);
        if (latest != null && latest.getCreatedAt().plusSeconds(resendCooldownSeconds).isAfter(now)
                && latest.getInvalidatedAt() == null) {
            return new EmailVerificationSendResponse(latest.getChallengeId(), latest.getExpiresAt());
        }
        challengeRepository.findByEmailIgnoreCaseAndConsumedAtIsNullAndInvalidatedAtIsNull(email)
                .forEach(value -> value.setInvalidatedAt(now));
        String code = String.format(Locale.ROOT, "%06d", SECURE_RANDOM.nextInt(1_000_000));
        EmailVerificationChallenge challenge = challengeRepository.save(EmailVerificationChallenge.builder()
                .challengeId(UUID.randomUUID()).email(email).codeHash(hash(code))
                .expiresAt(now.plus(Duration.ofMinutes(tokenTtlMinutes))).failedAttempts((short) 0)
                .createdAt(now).build());
        verificationEmailSender.sendVerificationCode(email, code);
        return new EmailVerificationSendResponse(challenge.getChallengeId(), challenge.getExpiresAt());
    }

    public EmailVerificationConfirmResponse confirmCode(String rawEmail, String code) {
        return confirmCode(rawEmail, code, "unknown");
    }

    @Transactional(noRollbackFor = ApiException.class)
    public EmailVerificationConfirmResponse confirmCode(String rawEmail, String code, String clientAddress) {
        String email = normalizeEmail(rawEmail);
        enforceQuota("confirm", email, clientAddress,
                CONFIRM_EMAIL_HOURLY_LIMIT, CONFIRM_EMAIL_DAILY_LIMIT,
                CONFIRM_IP_HOURLY_LIMIT, CONFIRM_IP_DAILY_LIMIT);
        EmailVerificationChallenge challenge = challengeRepository.findFirstByEmailOrderByCreatedAtDesc(email)
                .orElseThrow(this::invalidCode);
        Instant now = Instant.now();
        if (challenge.getConsumedAt() != null || challenge.getInvalidatedAt() != null
                || challenge.getConfirmedAt() != null || !challenge.getExpiresAt().isAfter(now)
                || challenge.getFailedAttempts() >= MAX_CODE_ATTEMPTS) {
            throw invalidCode();
        }
        if (!MessageDigest.isEqual(challenge.getCodeHash().getBytes(StandardCharsets.UTF_8),
                hash(code).getBytes(StandardCharsets.UTF_8))) {
            challenge.setFailedAttempts((short) (challenge.getFailedAttempts() + 1));
            if (challenge.getFailedAttempts() >= MAX_CODE_ATTEMPTS) challenge.setInvalidatedAt(now);
            throw invalidCode();
        }
        String ticket = newToken();
        challenge.setTicketHash(hash(ticket));
        challenge.setConfirmedAt(now);
        return new EmailVerificationConfirmResponse(ticket, challenge.getExpiresAt());
    }

    /** Called inside the signup transaction, so ticket consumption rolls back if any user write fails. */
    @Transactional
    public void consumeSignupTicket(String rawEmail, String ticket) {
        EmailVerificationChallenge challenge = challengeRepository.findByTicketHash(hash(ticket))
                .orElseThrow(this::invalidTicket);
        Instant now = Instant.now();
        if (!challenge.getEmail().equals(normalizeEmail(rawEmail)) || challenge.getConfirmedAt() == null
                || challenge.getConsumedAt() != null || challenge.getInvalidatedAt() != null
                || !challenge.getExpiresAt().isAfter(now)) {
            throw invalidTicket();
        }
        challenge.setConsumedAt(now);
    }

    @Transactional
    public void issueAndSend(User user) {
        requireAvailable();
        if (user.getEmailVerifiedAt() != null) return;
        Instant now = Instant.now();
        invalidateActiveTokens(user.getUserId(), now);
        String rawToken = newToken();
        tokenRepository.save(EmailVerificationToken.builder().userId(user.getUserId()).tokenHash(hash(rawToken))
                .expiresAt(now.plus(Duration.ofMinutes(tokenTtlMinutes))).createdAt(now).build());
        String baseUrl = verificationBaseUrl.endsWith("/")
                ? verificationBaseUrl.substring(0, verificationBaseUrl.length() - 1) : verificationBaseUrl;
        verificationEmailSender.sendVerificationLink(user.getEmail(), baseUrl + "/auth/email/verify?token=" + rawToken);
    }

    @Transactional
    public void resend(String email) {
        requireAvailable();
        User user = userRepository.findByEmail(normalizeEmail(email)).orElse(null);
        if (user == null || user.getEmailVerifiedAt() != null) return;
        Instant now = Instant.now();
        EmailVerificationToken latest = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getUserId()).orElse(null);
        if (latest != null && latest.getCreatedAt().plusSeconds(resendCooldownSeconds).isAfter(now)) return;
        issueAndSend(user);
    }

    @Transactional
    public void verify(String rawToken) {
        EmailVerificationToken token = tokenRepository.findByTokenHash(hash(rawToken)).orElseThrow(this::invalidToken);
        Instant now = Instant.now();
        if (token.getUsedAt() != null || token.getInvalidatedAt() != null || !token.getExpiresAt().isAfter(now))
            throw invalidToken();
        User user = userRepository.findById(token.getUserId()).orElseThrow(this::invalidToken);
        user.setEmailVerifiedAt(now);
        token.setUsedAt(now);
        invalidateActiveTokens(user.getUserId(), now);
    }

    private void enforceQuota(String action, String email, String clientAddress,
            int emailHourly, int emailDaily, int ipHourly, int ipDaily) {
        if (jdbcTemplate == null) return;
        String emailHash = hash("email:" + email);
        String ipHash = hash("ip:" + (clientAddress == null ? "unknown" : clientAddress));
        if (!claimQuota(action, emailHash, "hour", emailHourly)
                || !claimQuota(action, emailHash, "day", emailDaily)
                || !claimQuota(action, ipHash, "hour", ipHourly)
                || !claimQuota(action, ipHash, "day", ipDaily)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_RATE_LIMITED",
                    "인증 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private boolean claimQuota(String action, String scopeHash, String windowKind, int limit) {
        return !jdbcTemplate.queryForList("""
                insert into auth_verification_quota (
                    action, scope_hash, window_kind, window_start, request_count
                ) values (?, ?, ?, date_trunc(?, current_timestamp), 1)
                on conflict (action, scope_hash, window_kind, window_start) do update
                    set request_count = auth_verification_quota.request_count + 1
                    where auth_verification_quota.request_count < ?
                returning request_count
                """, action, scopeHash, windowKind, windowKind, limit).isEmpty();
    }

    private void requireAvailable() {
        if (!verificationEmailSender.isAvailable()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "EMAIL_DELIVERY_UNAVAILABLE", "이메일 인증 서비스를 일시적으로 사용할 수 없습니다.");
    }
    private void invalidateActiveTokens(UUID userId, Instant now) {
        List<EmailVerificationToken> active = tokenRepository.findByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(userId);
        active.forEach(token -> token.setInvalidatedAt(now));
    }
    private String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private ApiException invalidToken() { return new ApiException(HttpStatus.BAD_REQUEST,
            "INVALID_VERIFICATION_TOKEN", "인증 링크가 유효하지 않거나 만료되었습니다."); }
    private ApiException invalidCode() { return new ApiException(HttpStatus.BAD_REQUEST,
            "INVALID_VERIFICATION_CODE", "인증 코드가 유효하지 않거나 만료되었습니다."); }
    private ApiException invalidTicket() { return new ApiException(HttpStatus.BAD_REQUEST,
            "INVALID_VERIFICATION_TICKET", "이메일 인증 티켓이 유효하지 않거나 만료되었습니다."); }
    private String newToken() {
        byte[] bytes = new byte[32]; SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }
}
