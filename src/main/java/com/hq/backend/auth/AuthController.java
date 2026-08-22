package com.hq.backend.auth;

import com.hq.backend.auth.dto.CheckNicknameResponse;
import com.hq.backend.auth.dto.EmailVerificationConfirmRequest;
import com.hq.backend.auth.dto.EmailVerificationConfirmResponse;
import com.hq.backend.auth.dto.EmailVerificationSendRequest;
import com.hq.backend.auth.dto.EmailVerificationSendResponse;
import com.hq.backend.auth.dto.GoogleLoginRequest;
import com.hq.backend.auth.dto.LoginRequest;
import com.hq.backend.auth.dto.PasswordResetExecuteRequest;
import com.hq.backend.auth.dto.PasswordResetRequest;
import com.hq.backend.auth.dto.ResendVerificationRequest;
import com.hq.backend.auth.dto.SignupRequest;
import com.hq.backend.auth.dto.SignupResponse;
import com.hq.backend.auth.dto.TokenResponse;
import com.hq.backend.auth.dto.VerifyEmailRequest;
import com.hq.backend.common.auth.CurrentUserId;
import com.hq.backend.user.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final AccountService accountService;

    @PostMapping("/email/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    @PostMapping("/email/verification/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EmailVerificationSendResponse sendVerificationCode(
            @Valid @RequestBody EmailVerificationSendRequest request, HttpServletRequest httpRequest) {
        return authService.sendVerificationCode(request.email(), clientAddress(httpRequest));
    }

    @PostMapping("/email/verification/confirm")
    public EmailVerificationConfirmResponse confirmVerificationCode(
            @Valid @RequestBody EmailVerificationConfirmRequest request, HttpServletRequest httpRequest) {
        return authService.confirmVerificationCode(request.email(), request.code(), clientAddress(httpRequest));
    }

    @PostMapping("/email/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.token());
    }

    /** Browser-facing verification link. The raw token is accepted only over HTTPS in deployment. */
    @GetMapping(value = "/email/verify", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> verifyEmailLink(@RequestParam String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok("<html><body><h2>이메일 인증이 완료되었습니다.</h2><p>앱으로 돌아가 로그인해주세요.</p></body></html>");
    }

    @PostMapping("/email/verify/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendEmailVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendEmailVerification(request.email());
    }

    @PostMapping("/email/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/google")
    public TokenResponse loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        return authService.loginWithGoogle(request);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new com.hq.backend.common.exception.ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "refreshToken이 필요합니다.");
        }
        return authService.refresh(refreshToken);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CurrentUserId UUID userId) {
        authService.logout(userId);
    }

    @PostMapping("/password/reset-request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        // TODO: rate-limit
        passwordResetService.requestReset(request.email());
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody PasswordResetExecuteRequest request) {
        passwordResetService.executeReset(request.token(), request.newPassword());
    }

    private String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Real-IP");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.trim();
    }

    @GetMapping("/check-nickname")
    public CheckNicknameResponse checkNickname(@RequestParam String value) {
        // TODO: rate-limit
        boolean available = accountService.isNicknameAvailable(value);
        return new CheckNicknameResponse(available);
    }
}
