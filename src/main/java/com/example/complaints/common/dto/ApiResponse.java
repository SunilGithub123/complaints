package com.example.complaints.common.dto;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Standard envelope for every HTTP response. See TECHNICAL_DESIGN.md §5.
 * Exactly one of {@code data} (on success) or {@code error} (on failure) is non-null.
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorResponse error,
        OffsetDateTime timestamp
) {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, OffsetDateTime.now(IST));
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null, OffsetDateTime.now(IST));
    }

    public static <T> ApiResponse<T> error(ErrorResponse error) {
        return new ApiResponse<>(false, null, error, OffsetDateTime.now(IST));
    }
}

