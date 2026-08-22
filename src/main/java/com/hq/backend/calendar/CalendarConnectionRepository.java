package com.hq.backend.calendar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, UUID> {

    Optional<CalendarConnection> findByUserIdAndProvider(UUID userId, String provider);
    List<CalendarConnection> findByUserIdAndRevokedAtIsNullOrderByConnectedAtDesc(UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CalendarConnection c set c.syncToken = :next, c.lastSyncedAt = CURRENT_TIMESTAMP where c.calendarConnectionId = :id and c.syncToken is null")
    int setInitialSyncTokenIfAbsent(@Param("id") UUID id, @Param("next") String next);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CalendarConnection c set c.syncToken = :next, c.lastSyncedAt = CURRENT_TIMESTAMP where c.calendarConnectionId = :id and c.syncToken = :expected")
    int replaceSyncToken(@Param("id") UUID id, @Param("expected") String expected, @Param("next") String next);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CalendarConnection c set c.syncToken = null where c.calendarConnectionId = :id and c.syncToken = :expected")
    int clearSyncToken(@Param("id") UUID id, @Param("expected") String expected);
}
