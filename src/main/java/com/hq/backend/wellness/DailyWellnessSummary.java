package com.hq.backend.wellness;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 일일 마무리 카드. dwlScore는 저장·내부 분석용이고 클라이언트는 dwlBand만 노출한다(D5,
// 절대 원칙 3 — 점수 노출은 건강 점수로 오해될 여지). outdoorSource는 ERD 원본엔 없지만
// API 명세 §12.4가 "추정/실측" 구분 표기를 요구해 추가했다(estimated|observed).
// cardMessageSnapshot은 실제 렌더된 문장을 보존 — 사후 콘텐츠 검토가 성립하려면 필요하다.
@Entity
@Table(name = "daily_wellness_summary")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyWellnessSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID summaryId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private LocalDate summaryDate;

    @Column(nullable = false)
    private int eventCount;

    @Column(nullable = false)
    private int totalOutdoorMinutes;

    @Column(nullable = false)
    private String outdoorSource; // estimated | observed

    // 정시 도착 횟수와 그 분모. on_time만 정시로 세고 unknown은 분모에서도 빠진다.
    // 0회와 "결과를 모름"을 구분하려고 표본 수를 같이 저장한다.
    @Column(nullable = false)
    private int onTimeCount;

    @Column(nullable = false)
    private int arrivalSampleCount;

    private BigDecimal avgWisWeighted;

    private BigDecimal avgRls;

    // WIS/RLS 원천이 모두 없으면 null/unknown — 데이터 부재를 low로 오인하지 않는다.
    private Short dwlScore; // 0~100 또는 null, 내부용

    @Column(nullable = false)
    private String dwlBand; // low | mid | high | unknown, 클라이언트 노출용

    @Column(nullable = false)
    private String cardScenario; // default | exposure | density | rushed | stable

    @Column(nullable = false)
    private String cardMessageSnapshot;

    @Setter
    @Column(nullable = false)
    private boolean isViewed;

    @Column(nullable = false)
    private Instant createdAt;
}
