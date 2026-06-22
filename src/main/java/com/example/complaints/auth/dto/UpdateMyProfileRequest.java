package com.example.complaints.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-service "edit my profile" payload — {@code PUT /api/v1/staff/me}.
 *
 * <p>The caller can update only their <em>own</em> mutable profile fields.
 * Immutable for self-edit: {@code employeeId}, {@code role}, {@code subdivisionId},
 * {@code distributionCenterId} (scope changes are an ADMIN action via
 * {@code PUT /api/v1/admin/staff/{id}}; the BE log Stage 8b notes this).</p>
 */
public record UpdateMyProfileRequest(
        @NotBlank @Size(max = 200) String fullName,

        @Email @Size(max = 200) String email,

        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "mobile must be 7-15 digits, optional leading +")
        String mobile,

        @NotNull Boolean notificationsPushEnabled
) {}

