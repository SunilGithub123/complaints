package com.example.complaints.auth.dto;

import com.example.complaints.auth.model.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Admin → "create a staff account" payload.
 *
 * <p>Business rules enforced in the service layer:</p>
 * <ul>
 *   <li>ADMIN: {@code distributionCenterId} must be {@code null}; one active admin per subdivision.</li>
 *   <li>ENGINEER: {@code distributionCenterId} required; one active engineer per DC.</li>
 *   <li>TECHNICIAN: {@code distributionCenterId} required; many per DC allowed.</li>
 * </ul>
 *
 * <p>Initial password is server-generated and returned (once) in
 * {@link ResetStaffPasswordResponse}. New accounts always start with
 * {@code passwordResetRequired = true}.</p>
 */
public record CreateStaffRequest(
        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "^[A-Z0-9-]+$",
                message = "employeeId must be uppercase alphanumerics or hyphens")
        String employeeId,

        @NotBlank @Size(max = 200) String fullName,

        @NotNull UserRole role,

        @Email @Size(max = 200) String email,

        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "mobile must be 7-15 digits, optional leading +")
        String mobile,

        @NotNull Long subdivisionId,

        Long distributionCenterId
) {}

