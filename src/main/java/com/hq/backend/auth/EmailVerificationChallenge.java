package com.hq.backend.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "email_verification_challenge")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerificationChallenge {
    @Id
    private UUID challengeId;
    @Column(nullable = false)
    private String email;
    @Column(nullable = false)
    private String codeHash;
    @Setter private String ticketHash;
    @Column(nullable = false)
    private Instant expiresAt;
    @Setter private Instant confirmedAt;
    @Setter private Instant consumedAt;
    @Setter private Instant invalidatedAt;
    @Setter @Column(nullable = false)
    private short failedAttempts;
    @Column(nullable = false)
    private Instant createdAt;
}
