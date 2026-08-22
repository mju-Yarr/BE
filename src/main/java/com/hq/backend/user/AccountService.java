package com.hq.backend.user;

import com.hq.backend.auth.PasswordResetService;
import com.hq.backend.auth.RefreshTokenRepository;
import com.hq.backend.common.exception.ApiException;
import com.hq.backend.user.dto.ChangeNicknameResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService {

    // 닉네임: 2~12자, 한글·영문·숫자·언더스코어만 허용 (특수문자 제한)
    private static final Pattern NICKNAME_PATTERN = Pattern.compile("^[가-힣a-zA-Z0-9_]{2,12}$");

    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetService passwordResetService;

    /**
     * 비밀번호 변경 (로그인 상태). 현재 비밀번호 검증 → 새 비밀번호 길이 검증 →
     * passwordHash 갱신 → passwordUpdatedAt 갱신 → 현재 세션 제외 나머지 revoke.
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword,
                               String currentRefreshTokenHash) {
        UserCredential credential = userCredentialRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                        "사용자를 찾을 수 없습니다."));

        if (!passwordEncoder.matches(currentPassword, credential.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_PASSWORD",
                    "현재 비밀번호가 올바르지 않습니다.");
        }

        if (newPassword == null || newPassword.length() < 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD",
                    "새 비밀번호는 10자 이상이어야 합니다.");
        }

        Instant now = Instant.now();
        credential.setPasswordHash(passwordEncoder.encode(newPassword));
        credential.setPasswordUpdatedAt(now);

        // 현재 요청의 refreshToken을 제외한 나머지 전부 revoke
        if (currentRefreshTokenHash != null) {
            refreshTokenRepository.revokeAllByUserIdExcept(userId, currentRefreshTokenHash);
        } else {
            refreshTokenRepository.revokeAllByUserId(userId);
        }
    }

    /**
     * 닉네임 변경. 2~12자, 특수문자 제한 검증 후 갱신.
     */
    @Transactional
    public ChangeNicknameResponse changeNickname(UUID userId, String nickname) {
        validateNickname(nickname);
        String normalized = nickname.trim();

        if (userRepository.existsByNicknameIgnoreCase(normalized)) {
            throw new ApiException(HttpStatus.CONFLICT, "NICKNAME_EXISTS",
                    "이미 사용 중인 닉네임입니다.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                        "사용자를 찾을 수 없습니다."));

        user.setNickname(normalized);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException conflict) {
            throw new ApiException(HttpStatus.CONFLICT, "NICKNAME_EXISTS", "이미 사용 중인 닉네임입니다.");
        }
        return new ChangeNicknameResponse(normalized);
    }

    /**
     * 닉네임 사용 가능 여부 확인.
     */
    @Transactional(readOnly = true)
    public boolean isNicknameAvailable(String nickname) {
        return !userRepository.existsByNicknameIgnoreCase(nickname.trim());
    }

    /**
     * 이메일 변경 요청. 현재 비밀번호 확인 후 새 이메일로 인증 토큰 발송.
     */
    @Transactional
    public void requestEmailChange(UUID userId, String newEmail, String password) {
        UserCredential credential = userCredentialRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                        "사용자를 찾을 수 없습니다."));

        if (!passwordEncoder.matches(password, credential.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_PASSWORD",
                    "비밀번호가 올바르지 않습니다.");
        }

        if (userRepository.existsByEmail(newEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS",
                    "이미 사용 중인 이메일입니다.");
        }

        passwordResetService.issueEmailChangeToken(userId, newEmail);
    }

    /**
     * 이메일 변경 확인. 토큰 검증 → User.email + UserIdentity email providerUid 갱신.
     */
    @Transactional
    public void confirmEmailChange(String rawToken) {
        PasswordResetService.EmailChangeResult result =
                passwordResetService.consumeEmailChangeToken(rawToken);
        UUID userId = result.userId();
        String newEmail = result.newEmail();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                        "사용자를 찾을 수 없습니다."));

        if (userRepository.existsByEmail(newEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS",
                    "이미 사용 중인 이메일입니다.");
        }

        String oldEmail = user.getEmail();
        user.setEmail(newEmail);

        // UserIdentity의 email provider providerUid도 갱신
        userIdentityRepository.findByProviderAndProviderUid("email", oldEmail)
                .ifPresent(identity -> identity.setProviderUid(newEmail));
    }

    private void validateNickname(String nickname) {
        if (nickname == null || !NICKNAME_PATTERN.matcher(nickname).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NICKNAME",
                    "닉네임은 2~12자의 한글, 영문, 숫자, 밑줄만 사용할 수 있습니다.");
        }
    }
}
