package com.example.complaints.common.exception;

import com.example.complaints.common.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targeted coverage for the {@link MaxUploadSizeExceededException} handler — the only handler
 * in {@link GlobalExceptionHandler} that maps an infrastructure-level Spring exception onto a
 * business {@link ErrorCode}. Other handlers in this class are covered indirectly by the per-
 * controller WebMvcTest happy/unhappy pairs (Stage 1–10 pattern).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("MaxUploadSizeExceededException is mapped to IMAGE_TOO_LARGE (413)")
    void maxUploadSize_mapsToImageTooLarge() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleMaxUploadSize(
                new MaxUploadSizeExceededException(1_048_576L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        ApiResponse<Void> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.error()).isNotNull();
        assertThat(body.error().code()).isEqualTo(ErrorCode.IMAGE_TOO_LARGE.name());
    }
}

