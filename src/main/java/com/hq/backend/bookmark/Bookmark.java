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
@Table(name = "bookmark")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Bookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID bookmarkId;

    @Column(nullable = false)
    private UUID userId;

    @Setter
    @Column(nullable = false)
    private String placeName;

    @Setter
    private String address;

    @Setter
    @Column(nullable = false)
    private BigDecimal lat;

    @Setter
    @Column(nullable = false)
    private BigDecimal lng;

    @Setter
    private String folder;

    @Setter
    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private Instant createdAt;

    @Setter
    @Column(nullable = false)
    private Instant updatedAt;
}
