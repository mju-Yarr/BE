package com.hq.backend.plan;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 단위는 분(TRD API §10.1 — v3.0의 초 단위 표기는 폐기됨). routePayload는 재조회 키만 담고
// 원본 응답 전체는 저장하지 않는다.
@Entity
@Table(name = "route_option")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteOption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID routeOptionId;

    @Column(nullable = false)
    private UUID planId;

    @Column(nullable = false)
    private int routeRank;

    @Column(nullable = false)
    private String routeType; // fastest | least_walk | least_transfer

    @Column(nullable = false)
    private int totalMinutes;

    @Column(nullable = false)
    private int walkMinutes;

    @Column(nullable = false)
    private int transferCount;

    private Instant departAt;
    private Instant arriveAt;

    @JdbcTypeCode(SqlTypes.JSON)
    private String routePayload;

    @Column(nullable = false)
    private String provider;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String legs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String degraded;

    private String rawRef;
}
