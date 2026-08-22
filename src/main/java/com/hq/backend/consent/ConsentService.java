package com.hq.backend.consent;

import com.hq.backend.consent.dto.ConsentRequest;
import com.hq.backend.consent.dto.ConsentResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// ERD v3 전환에 맞춘 최소 수정 — user_consent(action:문자열, idempotency_key) 매핑을
// 반영했다. 멱등성 키는 컨트롤러가 Idempotency-Key 헤더에서 받아 그대로 전달한다.
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final UserConsentRepository userConsentRepository;

    @Transactional
    public ConsentResponse record(UUID userId, UUID idempotencyKey, ConsentRequest request) {
        UserConsent saved = userConsentRepository.save(UserConsent.builder()
                .userId(userId)
                .consentType(request.consentType().name().toLowerCase())
                .policyVersion(request.policyVersion())
                .action(request.agreed() ? "agreed" : "revoked")
                .isRequired(request.consentType() != ConsentType.MARKETING)
                .idempotencyKey(idempotencyKey)
                .recordedAt(Instant.now())
                .build());

        return new ConsentResponse(
                saved.getConsentEventId(),
                ConsentType.valueOf(saved.getConsentType().toUpperCase()),
                "agreed".equals(saved.getAction()),
                saved.getRecordedAt());
    }
}
