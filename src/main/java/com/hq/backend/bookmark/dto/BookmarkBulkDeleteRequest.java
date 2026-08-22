package com.hq.backend.bookmark.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record BookmarkBulkDeleteRequest(@NotEmpty List<UUID> bookmarkIds) {}
