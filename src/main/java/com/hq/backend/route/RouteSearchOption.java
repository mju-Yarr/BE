package com.hq.backend.route;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * CAL-05에서만 쓰이는 임시 route option snapshot이다. 계획에 속한 route_option과 달리
 * 검색 사용자에게만 보이며, expiresAt 이후 또는 event 생성에 한 번 소비된 뒤에는 재사용할 수 없다.
 */
@Entity
@Table(name = "route_search_option")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteSearchOption {

    @Id
    private UUID routeSearchOptionId;

    @Column(nullable = false)
    private UUID searchSessionId;

    @Column(nullable = false)
    private UUID userId;

    private UUID originPlaceId;

    @Column(nullable = false)
    private String originName;

    @Column(nullable = false)
    private double originLat;

    @Column(nullable = false)
    private double originLng;

    @Column(nullable = false)
    private double destinationLat;

    @Column(nullable = false)
    private double destinationLng;

    private String destinationName;

    @Column(nullable = false)
    private String anchorMode;

    @Column(nullable = false)
    private Instant requestedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private int routeRank;

    @Column(nullable = false)
    private String routeType;

    @Column(nullable = false)
    private int totalSeconds;

    @Column(nullable = false)
    private int walkSeconds;

    @Column(nullable = false)
    private int transferCount;

    @Column(nullable = false)
    private int outdoorSeconds;

    private Instant departAt;
    private Instant arriveAt;

    @Column(nullable = false)
    private String provider;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String legs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String degraded;

    private String rawRef;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant consumedAt;
    private UUID consumedPlanId;
}
