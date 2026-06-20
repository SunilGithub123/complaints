package com.example.complaints.common.dto;

import java.util.Map;

/**
 * Failure detail payload sent inside {@link ApiResponse#error()}.
 * {@code code} corresponds to a {@link com.example.complaints.common.exception.ErrorCode} enum value.
 * {@code details} is a free-form map for field-level errors, conflicting IDs, etc.
 */
public record ErrorResponse(
        String code,
        String message,
        Map<String, Object> details
) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null);
    }

    public static ErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(code, message, details);
    }
}

