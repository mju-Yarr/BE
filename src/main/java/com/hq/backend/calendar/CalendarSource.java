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

@Entity
@Table(name = "calendar_source")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CalendarSource {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID calendarSourceId;
    @Column(nullable = false)
    private UUID calendarConnectionId;
    @Column(nullable = false)
    private String externalCalendarId;
    @Column(nullable = false)
    private String displayName;
    @Setter @Column(nullable = false)
    private boolean isWritable;
    @Setter @Column(nullable = false)
    private boolean isDefault;
    @Setter @Column(nullable = false)
    private boolean syncEnabled;
    @Setter private String externalEtag;
    @Setter private Instant deletedAt;
}
