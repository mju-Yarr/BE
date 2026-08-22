package com.hq.backend.bookmark;

import com.hq.backend.bookmark.dto.BookmarkBulkDeleteRequest;
import com.hq.backend.bookmark.dto.BookmarkCreateRequest;
import com.hq.backend.bookmark.dto.BookmarkPatchRequest;
import com.hq.backend.bookmark.dto.BookmarkResponse;
import com.hq.backend.common.auth.CurrentUserId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me/bookmarks")
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;

    @GetMapping
    public List<BookmarkResponse> list(@CurrentUserId UUID userId,
                                       @RequestParam(required = false) String folder) {
        return bookmarkService.list(userId, folder);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookmarkResponse create(@CurrentUserId UUID userId,
                                   @Valid @RequestBody BookmarkCreateRequest request) {
        return bookmarkService.create(userId, request);
    }

    @PatchMapping("/{id}")
    public BookmarkResponse patch(@CurrentUserId UUID userId, @PathVariable UUID id,
            @Valid @RequestBody BookmarkPatchRequest request) {
        return bookmarkService.patch(userId, id, request);
    }

    @PostMapping("/bulk-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bulkDelete(@CurrentUserId UUID userId,
            @Valid @RequestBody BookmarkBulkDeleteRequest request) {
        bookmarkService.bulkDelete(userId, request.bookmarkIds());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        bookmarkService.delete(userId, id);
    }
}
