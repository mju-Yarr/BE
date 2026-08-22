package com.hq.backend.bookmark;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BookmarkRepository extends JpaRepository<Bookmark, UUID> {
    List<Bookmark> findAllByUserIdOrderBySortOrderAscCreatedAtDesc(UUID userId);
    List<Bookmark> findAllByUserId(UUID userId);
    List<Bookmark> findAllByUserIdAndFolderOrderBySortOrderAscCreatedAtDesc(UUID userId, String folder);
    Optional<Bookmark> findByBookmarkIdAndUserId(UUID bookmarkId, UUID userId);
    boolean existsByUserIdAndLatAndLng(UUID userId, BigDecimal lat, BigDecimal lng);

    @Modifying
    @Query("delete from Bookmark b where b.userId = :userId and b.bookmarkId in :ids")
    int deleteOwnedByIds(UUID userId, Collection<UUID> ids);
}
