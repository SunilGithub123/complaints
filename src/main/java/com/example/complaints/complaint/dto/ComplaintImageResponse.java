package com.example.complaints.complaint.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Image descriptor returned alongside a complaint. {@code url} is a signed read URL from
 * {@code StorageService.signedReadUrl(...)} — short-lived, regenerate on every read.
 */
public record ComplaintImageResponse(
        Long id,
        String contentType,
        int sizeBytes,
        String url,
        OffsetDateTime uploadedAt
) {
    public static List<ComplaintImageResponse> emptyList() {
        return List.of();
    }
}

