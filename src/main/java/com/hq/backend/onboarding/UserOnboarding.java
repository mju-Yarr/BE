package com.hq.backend.onboarding;

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
@Table(name = "user_onboarding")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserOnboarding {
    @Id private UUID userId;
    @Setter @Column(nullable = false) private String currentStep;
    @Setter private Instant completedAt;
    @Setter private Instant coachmarkSeenAt;
    @Setter @Column(nullable = false) private Instant updatedAt;
}
