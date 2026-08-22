package com.hq.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.hq.backend.auth.PasswordResetService;
import com.hq.backend.auth.PasswordResetToken;
import com.hq.backend.auth.PasswordResetTokenRepository;
import com.hq.backend.auth.RefreshToken;
import com.hq.backend.auth.RefreshTokenRepository;
import com.hq.backend.bookmark.RecentDestinationRepository;
import com.hq.backend.bookmark.RecentDestinationService;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.common.util.TokenHashUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 동시성 회귀 테스트 — PESSIMISTIC_WRITE 락과 트랜잭션의 최종 상태를 PostgreSQL에서 검증한다.
 */
@SpringBootTest
@DirtiesContext
class ConcurrencyIntegrationTest {

    private static final String SUCCESS = "SUCCESS";

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialRepository userCredentialRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private AccountManagementService accountManagementService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private RecentDestinationService recentDestinationService;

    @Autowired
    private RecentDestinationRepository recentDestinationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * #190: 두 요청이 동시에 각각의 provider를 해제해도 마지막 identity는 보존해야 한다.
     */
    @Test
    void 동시에_두_provider를_해제하면_하나는_LAST_IDENTITY로_실패한다() throws Exception {
        String email = "concurrency-provider-" + UUID.randomUUID() + "@test.com";
        UUID userId = createTestUser(email);
        UUID emailIdentityId = createIdentity(userId, "email", email);
        UUID googleIdentityId = createIdentity(userId, "google", "google-" + UUID.randomUUID());

        List<String> results = runConcurrently(List.of(
                () -> accountManagementService.unlinkProvider(userId, emailIdentityId),
                () -> accountManagementService.unlinkProvider(userId, googleIdentityId)));

        assertThat(results).containsExactlyInAnyOrder(SUCCESS, "LAST_IDENTITY");
        assertThat(userIdentityRepository.findAllByUserIdAndRevokedAtIsNull(userId)).hasSize(1);
        assertThat(userIdentityRepository.findAllById(List.of(emailIdentityId, googleIdentityId)))
                .hasSize(2)
                .filteredOn(identity -> identity.getRevokedAt() != null)
                .hasSize(1);
    }

    @Test
    void 동시에_같은_닉네임으로_변경하면_하나만_성공한다() throws Exception {
        UUID firstUser = createTestUser("nickname-first-" + UUID.randomUUID() + "@test.com");
        UUID secondUser = createTestUser("nickname-second-" + UUID.randomUUID() + "@test.com");
        String nickname = "race_" + UUID.randomUUID().toString().substring(0, 6);

        List<String> results = runConcurrently(List.of(
                () -> accountService.changeNickname(firstUser, nickname),
                () -> accountService.changeNickname(secondUser, nickname.toUpperCase())));

        assertThat(results).containsExactlyInAnyOrder(SUCCESS, "NICKNAME_EXISTS");
        assertThat(userRepository.findAll()).filteredOn(user -> user.getNickname().equalsIgnoreCase(nickname))
                .hasSize(1);
    }

    @Test
    void 최근_목적지_동시기록은_한행에서_useCount를_원자적으로_증가시킨다() throws Exception {
        UUID userId = createTestUser("recent-" + UUID.randomUUID() + "@test.com");

        List<String> results = runConcurrently(List.of(
                () -> recentDestinationService.record(userId, "강남역", "서울", 37.498, 127.027),
                () -> recentDestinationService.record(userId, "강남역", "서울", 37.498, 127.027)));

        assertThat(results).containsExactly(SUCCESS, SUCCESS);
        var destination = recentDestinationRepository.findByUserIdAndLatAndLng(userId,
                new java.math.BigDecimal("37.498000"), new java.math.BigDecimal("127.027000")).orElseThrow();
        assertThat(destination.getUseCount()).isEqualTo(2);
    }

    /**
     * #193: 같은 reset 토큰은 한 트랜잭션만 소비하고 비밀번호 변경과 세션 폐기를 함께 커밋해야 한다.
     */
    @Test
    void 동시에_같은_토큰으로_비밀번호_재설정하면_하나만_성공한다() throws Exception {
        UUID userId = createTestUser("concurrency-reset-" + UUID.randomUUID() + "@test.com");
        String oldPassword = "OldPassword123!";
        String firstPassword = "NewPassword0!!";
        String secondPassword = "NewPassword1!!";
        createCredential(userId, oldPassword);

        RefreshToken firstSession = createRefreshToken(userId);
        RefreshToken secondSession = createRefreshToken(userId);
        String rawToken = UUID.randomUUID().toString();
        createResetToken(userId, rawToken);

        List<String> results = runConcurrently(List.of(
                () -> passwordResetService.executeReset(rawToken, firstPassword),
                () -> passwordResetService.executeReset(rawToken, secondPassword)));

        assertThat(results).containsExactlyInAnyOrder(SUCCESS, "INVALID_TOKEN");

        PasswordResetToken consumedToken = tokenRepository.findByTokenHash(TokenHashUtil.sha256(rawToken))
                .orElseThrow();
        assertThat(consumedToken.getConsumedAt()).isNotNull();

        UserCredential credential = userCredentialRepository.findById(userId).orElseThrow();
        assertThat(passwordEncoder.matches(oldPassword, credential.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches(firstPassword, credential.getPasswordHash())
                ^ passwordEncoder.matches(secondPassword, credential.getPasswordHash())).isTrue();

        assertThat(refreshTokenRepository.findAllById(List.of(firstSession.getId(), secondSession.getId())))
                .allSatisfy(session -> assertThat(session.getRevokedAt()).isNotNull());
    }

    private List<String> runConcurrently(List<ThrowingAction> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        try {
            for (ThrowingAction action : actions) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        action.run();
                        return SUCCESS;
                    } catch (ApiException exception) {
                        return exception.getCode();
                    }
                }));
            }

            ready.await();
            start.countDown();

            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private UUID createTestUser(String email) {
        User user = userRepository.save(User.builder()
                .email(email)
                .nickname("test-" + UUID.randomUUID().toString().substring(0, 6))
                .timezone("Asia/Seoul")
                .createdAt(Instant.now())
                .emailVerifiedAt(Instant.now())
                .accountStatus("active")
                .build());
        return user.getUserId();
    }

    private UUID createIdentity(UUID userId, String provider, String providerUid) {
        UserIdentity identity = userIdentityRepository.save(UserIdentity.builder()
                .userId(userId)
                .provider(provider)
                .providerUid(providerUid)
                .linkedAt(Instant.now())
                .build());
        return identity.getIdentityId();
    }

    private void createCredential(UUID userId, String password) {
        userCredentialRepository.save(UserCredential.builder()
                .userId(userId)
                .passwordHash(passwordEncoder.encode(password))
                .passwordAlgo("argon2id")
                .passwordUpdatedAt(Instant.now())
                .failedAttempts((short) 0)
                .build());
    }

    private void createResetToken(UUID userId, String rawToken) {
        Instant now = Instant.now();
        tokenRepository.save(PasswordResetToken.builder()
                .userId(userId)
                .tokenHash(TokenHashUtil.sha256(rawToken))
                .type("password_reset")
                .expiresAt(now.plusSeconds(1800))
                .createdAt(now)
                .build());
    }

    private RefreshToken createRefreshToken(UUID userId) {
        return refreshTokenRepository.save(RefreshToken.create(
                userId,
                TokenHashUtil.sha256(UUID.randomUUID().toString()),
                Instant.now().plusSeconds(3600)));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
