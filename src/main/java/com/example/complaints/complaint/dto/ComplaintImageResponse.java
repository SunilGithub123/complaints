package com.example.complaints.complaint.dto;

import com.example.complaints.complaint.model.ComplaintImageType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Image descriptor returned alongside a complaint. {@code url} is a signed read URL from
 * {@code StorageService.signedReadUrl(...)} — short-lived, regenerate on every read.
 *
 * <p>{@code imageType} is the discriminator added in the Stage 16 follow-up: {@code COMPLAINT}
 * images are uploaded by the consumer at submission; {@code RESOLUTION} images are uploaded by
 * the technician at resolution. The FE renders them as separate gallery sections.</p>
 */
public record ComplaintImageResponse(
        Long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Source of the image: consumer-submitted vs technician-uploaded at resolution.")
        ComplaintImageType imageType,
        String contentType,
        int sizeBytes,
        String url,
        OffsetDateTime uploadedAt
) {
    public static List<ComplaintImageResponse> emptyList() {
        return List.of();
    }
}
