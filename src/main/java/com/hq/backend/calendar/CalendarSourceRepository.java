package com.hq.backend.calendar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarSourceRepository extends JpaRepository<CalendarSource, UUID> {
    List<CalendarSource> findByCalendarConnectionIdAndDeletedAtIsNullOrderByIsDefaultDescDisplayNameAsc(UUID connectionId);
    List<CalendarSource> findByCalendarConnectionIdInAndDeletedAtIsNull(List<UUID> connectionIds);
    Optional<CalendarSource> findByCalendarConnectionIdAndIsDefaultTrueAndDeletedAtIsNull(UUID connectionId);

    @Query("""
            select s from CalendarSource s, CalendarConnection c
            where s.calendarConnectionId = c.calendarConnectionId
              and s.calendarSourceId = :sourceId and c.userId = :userId
              and c.revokedAt is null and s.deletedAt is null
            """)
    Optional<CalendarSource> findOwnedActive(@Param("sourceId") UUID sourceId, @Param("userId") UUID userId);

    @Modifying
    @Query(value = """
            insert into calendar_source (
                calendar_source_id, calendar_connection_id, external_calendar_id, display_name,
                is_writable, is_default, sync_enabled
            ) values (gen_random_uuid(), :connectionId, :externalCalendarId, :displayName, true, true, true)
            on conflict (calendar_connection_id, external_calendar_id) do nothing
            """, nativeQuery = true)
    int insertDefaultSourceIfAbsent(
            @Param("connectionId") UUID connectionId,
            @Param("externalCalendarId") String externalCalendarId,
            @Param("displayName") String displayName);
}
