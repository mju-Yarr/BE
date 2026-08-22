package com.hq.backend.auth;

import com.hq.backend.common.exception.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** SMTP sender. Gmail credentials are injected only via the runtime environment. */
@Component
@ConditionalOnProperty(prefix = "app.email-verification.smtp", name = "enabled", havingValue = "true")
public class SmtpVerificationEmailSender implements VerificationEmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpVerificationEmailSender(JavaMailSender mailSender,
            @Value("${app.email-verification.smtp.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void sendVerificationCode(String recipientEmail, String code) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(recipientEmail);
            message.setSubject("Ensom 이메일 인증 코드");
            message.setText("Ensom 인증 코드는 " + code + " 입니다. 제한된 시간 동안 한 번만 사용할 수 있습니다.");
            mailSender.send(message);
        } catch (MailException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                    "인증 메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요.");
        }
    }

    @Override
    public void sendVerificationLink(String recipientEmail, String verificationLink) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(recipientEmail);
            message.setSubject("Ensom 이메일 인증");
            message.setText("아래 링크를 열어 이메일 인증을 완료하세요.\n\n"
                    + verificationLink + "\n\n"
                    + "이 링크는 제한된 시간 동안 한 번만 사용할 수 있습니다.");
            mailSender.send(message);
        } catch (MailException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                    "인증 메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요.");
        }
    }
}
