package com.hq.backend.calendar;

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

// ERD v3 CALENDAR_CONNECTION — 옛 calendar_connections에 있던 scope 컬럼이 없어졌고
// (API 응답도 scope를 안 준다), 대신 externalAccountId(구글 계정 식별자)가 필수다.
// (user_id, provider, external_account_id) 유니크 — CalendarService 참고.
@Entity
@Table(name = "calendar_connection")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CalendarConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID calendarConnectionId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String provider;

    @Setter
    @Column(nullable = false)
    private String externalAccountId;

    // BytesEncryptor로 암호화된 refresh_token — 평문은 절대 저장하지 않는다. 스키마상
    // nullable(재교환 시 구글이 refresh_token을 안 줄 수 있는 경우가 있어서).
    @Setter
    private byte[] refreshTokenEnc;

    @Setter
    @Column(nullable = false)
    private Instant connectedAt;

    @Setter private Instant revokedAt;

    @Setter private Instant lastSyncedAt;

    /** Google Calendar API syncToken — 증분 동기화에 사용. 초기값 null = full sync. */
    @Setter
    private String syncToken;
}
