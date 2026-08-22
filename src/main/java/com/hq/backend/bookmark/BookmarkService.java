package com.hq.backend.bookmark;

import com.hq.backend.bookmark.dto.BookmarkCreateRequest;
import com.hq.backend.bookmark.dto.BookmarkPatchRequest;
import com.hq.backend.bookmark.dto.BookmarkResponse;
import com.hq.backend.common.exception.ApiException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BookmarkService {
    private final BookmarkRepository bookmarkRepository;

    @Transactional(readOnly = true)
    public List<BookmarkResponse> list(UUID userId, String folder) {
        List<Bookmark> bookmarks = folder != null && !folder.isBlank()
                ? bookmarkRepository.findAllByUserIdAndFolderOrderBySortOrderAscCreatedAtDesc(userId, folder)
                : bookmarkRepository.findAllByUserIdOrderBySortOrderAscCreatedAtDesc(userId);
        return bookmarks.stream().map(BookmarkResponse::from).toList();
    }

    @Transactional
    public BookmarkResponse create(UUID userId, BookmarkCreateRequest request) {
        Instant now = Instant.now();
        Bookmark bookmark = bookmarkRepository.save(Bookmark.builder().userId(userId)
                .placeName(request.placeName().trim()).address(blankToNull(request.address()))
                .lat(request.lat()).lng(request.lng()).folder(blankToNull(request.folder()))
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                .createdAt(now).updatedAt(now).build());
        return BookmarkResponse.from(bookmark);
    }

    @Transactional
    public BookmarkResponse patch(UUID userId, UUID bookmarkId, BookmarkPatchRequest request) {
        Bookmark bookmark = findOwned(userId, bookmarkId);
        if (request.placeName() != null) {
            if (request.placeName().isBlank()) throw validation("placeName은 비울 수 없습니다.");
            bookmark.setPlaceName(request.placeName().trim());
        }
        if (request.address() != null) bookmark.setAddress(blankToNull(request.address()));
        if ((request.lat() == null) != (request.lng() == null)) throw validation("lat과 lng는 함께 지정해야 합니다.");
        if (request.lat() != null) { bookmark.setLat(request.lat()); bookmark.setLng(request.lng()); }
        if (request.folder() != null) bookmark.setFolder(blankToNull(request.folder()));
        if (request.sortOrder() != null) bookmark.setSortOrder(request.sortOrder());
        bookmark.setUpdatedAt(Instant.now());
        return BookmarkResponse.from(bookmark);
    }

    @Transactional
    public void bulkDelete(UUID userId, List<UUID> bookmarkIds) {
        var distinct = new HashSet<>(bookmarkIds);
        int deleted = bookmarkRepository.deleteOwnedByIds(userId, distinct);
        if (deleted != distinct.size()) throw new ApiException(HttpStatus.NOT_FOUND, "BOOKMARK_NOT_FOUND",
                "일부 북마크를 찾을 수 없습니다.");
    }

    @Transactional
    public void delete(UUID userId, UUID bookmarkId) { bookmarkRepository.delete(findOwned(userId, bookmarkId)); }

    private Bookmark findOwned(UUID userId, UUID bookmarkId) {
        return bookmarkRepository.findByBookmarkIdAndUserId(bookmarkId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BOOKMARK_NOT_FOUND", "북마크를 찾을 수 없습니다."));
    }
    private ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", message);
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
