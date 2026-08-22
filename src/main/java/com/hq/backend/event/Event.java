package com.hq.backend.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// ERD v3.1 `event`. 외부 캘린더 제목 원문은 저장하지 않는다(절대 원칙 8) — title 컬럼 자체가 없다.
// 표시명은 사용자가 입력·승인한 displayLabel만 (TRD D17). 구 Vium user_events(UserEvent)를
// 대체한다 — CalendarService의 캘린더 밀도 계산도 이제 이 엔티티를 쓴다.
@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID eventId;

    @Version
    @Column(nullable = false)
    private Long revision;

    @Column(nullable = false)
    private UUID userId;

    @Setter
    private UUID calendarSourceId;

    @Setter
    private String externalEventId;

    @Setter
    @Column(nullable = false)
    private String sourceType; // internal | external | map_search

    @Setter
    @Builder.Default
    @Column(nullable = false)
    private String anchorMode = "arrive_by"; // arrive_by | depart_at

    @Setter
    @Column(nullable = false)
    private Instant startsAt;

    @Setter
    private Instant endsAt;

    @Setter
    @Column(nullable = false)
    private boolean isAllDay;

    @Setter
    @Column(nullable = false)
    private String locationState; // required_resolved | required_missing | not_required | undecided

    @Setter
    private String destinationName;

    @Setter
    private String destinationAddress;

    @Setter
    private Double destinationLat;

    @Setter
    private Double destinationLng;

    @Setter
    private String meetingUrl;

    @Setter
    private String eventKind;

    @Setter
    private String displayLabel; // 사용자가 입력·승인한 값만. 외부 제목 원문은 절대 여기 들어가지 않는다

    @Setter
    @Column(nullable = false)
    private boolean autoManageExcluded;

    @Setter
    @Column(nullable = false)
    private boolean excludedFromLearning;

    @Setter
    @Column(nullable = false)
    private String status; // planned -> notified -> preparing -> enroute -> arrived -> closed (+ skipped/cancelled/unresolved)

    @Column(nullable = false)
    private Instant createdAt;

    @Setter
    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void initializeTimestamps() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }
}
