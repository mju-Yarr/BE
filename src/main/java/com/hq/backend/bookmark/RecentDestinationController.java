package com.hq.backend.bookmark;

import com.hq.backend.bookmark.dto.RecentDestinationResponse;
import com.hq.backend.common.auth.CurrentUserId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me/recent-destinations")
@RequiredArgsConstructor
public class RecentDestinationController {
    private final RecentDestinationService recentDestinationService;

    @GetMapping
    public List<RecentDestinationResponse> list(@CurrentUserId UUID userId,
            @RequestParam(defaultValue = "20") int limit) {
        return recentDestinationService.list(userId, limit);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@CurrentUserId UUID userId) {
        recentDestinationService.clear(userId);
    }
}
