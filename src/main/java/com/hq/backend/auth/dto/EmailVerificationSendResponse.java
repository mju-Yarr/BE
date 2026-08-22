package com.hq.backend.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record EmailVerificationSendResponse(UUID challengeId, Instant expiresAt) {}
