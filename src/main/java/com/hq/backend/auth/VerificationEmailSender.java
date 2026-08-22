package com.hq.backend.auth;

/** Delivers one-time verification material. Implementations must never persist or log raw values. */
public interface VerificationEmailSender {
    boolean isAvailable();
    void sendVerificationLink(String recipientEmail, String verificationLink);
    default void sendVerificationCode(String recipientEmail, String code) {
        throw new UnsupportedOperationException("Verification code delivery is not configured");
    }
}
