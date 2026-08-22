package com.hq.backend.bookmark;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecentDestinationRepository extends JpaRepository<RecentDestination, UUID> {
    List<RecentDestination> findByUserIdOrderByLastUsedAtDesc(UUID userId, Pageable pageable);
    Optional<RecentDestination> findByUserIdAndLatAndLng(UUID userId, BigDecimal lat, BigDecimal lng);

    @Modifying
    @Query(value = """
            insert into recent_destination (
                recent_destination_id, user_id, place_name, address, lat, lng, use_count, last_used_at
            ) values (gen_random_uuid(), :userId, :placeName, :address, :lat, :lng, 1, :lastUsedAt)
            on conflict (user_id, lat, lng) do update set
                place_name = excluded.place_name,
                address = excluded.address,
                use_count = recent_destination.use_count + 1,
                last_used_at = excluded.last_used_at
            """, nativeQuery = true)
    void upsert(@Param("userId") UUID userId, @Param("placeName") String placeName,
            @Param("address") String address, @Param("lat") BigDecimal lat,
            @Param("lng") BigDecimal lng, @Param("lastUsedAt") java.time.Instant lastUsedAt);

    void deleteByUserId(UUID userId);
}
