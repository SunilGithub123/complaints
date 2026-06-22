package com.example.complaints.complaint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * JSON form-part of the multipart submit request. Sent as the part named {@code complaint};
 * image parts ({@code images}) ride in the same multipart envelope (Stage 10b contract).
 *
 * @param consumerId  external EB consumer number; must match the verified consumer JWT
 * @param mobile      OTP-verified contact mobile (need not equal {@code consumer_master.mobile})
 * @param categoryId  active complaint-category FK
 * @param description free-form text, ≤ 4000 chars
 * @param location    optional free-form location hint, ≤ 500 chars
 */
public record SubmitComplaintRequest(
        @NotBlank @Size(max = 50)
        String consumerId,

        @NotBlank @Pattern(regexp = "^\\+?[0-9]{7,15}$")
        String mobile,

        @NotNull
        Long categoryId,

        @NotBlank @Size(max = 4000)
        String description,

        @Size(max = 500)
        String location
) {
}

