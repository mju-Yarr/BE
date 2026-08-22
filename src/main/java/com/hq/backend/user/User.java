package com.hq.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// ERD v3(신 ERD, USERS.user_id PK). provider/providerUid/passwordHash는 USER_IDENTITY/
// USER_CREDENTIAL로 이관됐다(chore/be-schema-core, #61) — 이 엔티티엔 남기지 않는다.
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id")
    private UUID userId;

    @Setter
    private String fullName;

    @Setter
    @Column(nullable = false)
    private String email;

    @Setter
    @Column(nullable = false)
    private String nickname;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false)
    private Instant createdAt;

    @Setter
    @Column(nullable = false)
    private String accountStatus; // active | withdrawn

    @Setter
    private UUID registrationInstallationId;

    @Setter
    private Instant emailVerifiedAt;

    @Setter
    private Instant withdrawnAt;

    @Setter
    private Instant deletedAt;
}
