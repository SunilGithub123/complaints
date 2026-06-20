package com.example.complaints.common.dto;

import com.example.complaints.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimum tests per `.github/copilot-instructions.md` — 1 happy path + 1 alternate path.
 */
class ApiResponseTest {

    @Test
    @DisplayName("ok(data) returns success=true with the data and a non-null IST timestamp")
    void okPayload() {
        ApiResponse<String> response = ApiResponse.ok("hello");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo("hello");
        assertThat(response.error()).isNull();
        assertThat(response.timestamp()).isNotNull();
        assertThat(response.timestamp().getOffset().getTotalSeconds())
                .as("timestamp must be in IST (+05:30)")
                .isEqualTo(5 * 3600 + 30 * 60);
    }

    @Test
    @DisplayName("error(...) returns success=false with an ErrorResponse and null data")
    void errorPayload() {
        ApiResponse<Void> response = ApiResponse.error(
                ErrorResponse.of(ErrorCode.CONSUMER_NOT_FOUND.name(), "missing"));

        assertThat(response.success()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.error().code()).isEqualTo("CONSUMER_NOT_FOUND");
    }
}

