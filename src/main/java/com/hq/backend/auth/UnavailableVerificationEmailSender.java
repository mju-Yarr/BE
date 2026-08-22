package com.hq.backend.auth;

import com.hq.backend.common.exception.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Safe default: do not create a non-verifiable email account when outbound mail is unavailable. */
@Component
@ConditionalOnProperty(prefix = "app.email-verification.smtp", name = "enabled", havingValue = "false", matchIfMissing = true)
public class UnavailableVerificationEmailSender implements VerificationEmailSender {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void sendVerificationCode(String recipientEmail, String code) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                "이메일 인증 서비스를 일시적으로 사용할 수 없습니다.");
    }

    @Override
    public void sendVerificationLink(String recipientEmail, String verificationLink) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                "이메일 인증 서비스를 일시적으로 사용할 수 없습니다.");
    }
}
