package com.hq.backend.auth;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface EmailVerificationChallengeRepository extends JpaRepository<EmailVerificationChallenge, UUID> {
    List<EmailVerificationChallenge> findByEmailIgnoreCaseAndConsumedAtIsNullAndInvalidatedAtIsNull(String email);
    Optional<EmailVerificationChallenge> findFirstByEmailIgnoreCaseOrderByCreatedAtDesc(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationChallenge> findFirstByEmailOrderByCreatedAtDesc(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationChallenge> findByTicketHash(String ticketHash);
}
