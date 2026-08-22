package com.hq.backend.auth.dto;

import java.time.Instant;

public record EmailVerificationConfirmResponse(String verificationTicket, Instant expiresAt) {}
