package com.example.complaints.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Admin → "edit a staff account" payload.
 *
 * <p>{@code employeeId}, {@code role}, and {@code subdivisionId} are immutable
 * post-creation — to move a staff member to a different subdivision, deactivate
 * the existing account and create a new one. {@code distributionCenterId} can
 * be reassigned only for ENGINEER / TECHNICIAN (and stays {@code null} for
 * ADMIN).</p>
 */
public record UpdateStaffRequest(
        @NotBlank @Size(max = 200) String fullName,

        @Email @Size(max = 200) String email,

        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "mobile must be 7-15 digits, optional leading +")
        String mobile,

        Long distributionCenterId
) {}

