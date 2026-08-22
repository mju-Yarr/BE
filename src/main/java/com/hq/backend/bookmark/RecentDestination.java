package com.hq.backend.bookmark;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "recent_destination")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecentDestination {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID recentDestinationId;
    @Column(nullable = false) private UUID userId;
    @Setter @Column(nullable = false) private String placeName;
    @Setter private String address;
    @Column(nullable = false) private BigDecimal lat;
    @Column(nullable = false) private BigDecimal lng;
    @Setter @Column(nullable = false) private int useCount;
    @Setter @Column(nullable = false) private Instant lastUsedAt;
}
