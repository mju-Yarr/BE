package com.hq.backend.bookmark;

import com.hq.backend.bookmark.dto.RecentDestinationResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@RequiredArgsConstructor
public class RecentDestinationService {
    private static final Logger log = LoggerFactory.getLogger(RecentDestinationService.class);

    private final RecentDestinationRepository recentDestinationRepository;
    private final BookmarkRepository bookmarkRepository;

    @Transactional
    public void record(UUID userId, String placeName, String address, Double lat, Double lng) {
        if (placeName == null || placeName.isBlank() || lat == null || lng == null) return;
        BigDecimal latitude = coordinate(lat);
        BigDecimal longitude = coordinate(lng);
        recentDestinationRepository.upsert(userId, placeName, address, latitude, longitude, Instant.now());
    }

    /**
     * Event 저장이 커밋된 뒤 별도 트랜잭션에서 보조 UX 데이터를 기록한다. 최근 목적지
     * 저장 실패가 일정 생성·수정 성공을 뒤집지 않으며, 원자 upsert가 동시 증가를 보존한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAfterCommit(RecordRequested request) {
        try {
            record(request.userId(), request.placeName(), request.address(), request.lat(), request.lng());
        } catch (RuntimeException failure) {
            log.warn("recent destination update failed after event commit: userId={}", request.userId(), failure);
        }
    }

    @Transactional(readOnly = true)
    public java.util.List<RecentDestinationResponse> list(UUID userId, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        Set<Coordinate> bookmarks = bookmarkRepository.findAllByUserId(userId).stream()
                .map(value -> new Coordinate(value.getLat(), value.getLng()))
                .collect(Collectors.toSet());
        return recentDestinationRepository.findByUserIdOrderByLastUsedAtDesc(userId, PageRequest.of(0, limit)).stream()
                .map(value -> new RecentDestinationResponse(value.getRecentDestinationId(), value.getPlaceName(),
                        value.getAddress(), value.getLat(), value.getLng(),
                        bookmarks.contains(new Coordinate(value.getLat(), value.getLng())), value.getLastUsedAt()))
                .toList();
    }

    @Transactional
    public void clear(UUID userId) { recentDestinationRepository.deleteByUserId(userId); }

    private BigDecimal coordinate(double value) {
        return BigDecimal.valueOf(value).setScale(6, java.math.RoundingMode.HALF_UP);
    }

    public record RecordRequested(
            UUID userId, String placeName, String address, Double lat, Double lng) { }

    private record Coordinate(BigDecimal lat, BigDecimal lng) { }
}
