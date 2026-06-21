package com.example.complaints.auth.dto;

import com.example.complaints.auth.model.UserRole;

import java.time.OffsetDateTime;

/**
 * Staff admin list / detail projection. Carries the columns the admin UI surfaces;
 * never includes the password hash.
 *
 * <p>All timestamps are IST {@link OffsetDateTime} on the wire (see {@code DateUtils.toIst}).</p>
 */
public record StaffListItemResponse(
        Long id,
        String employeeId,
        String fullName,
        UserRole role,
        String email,
        String mobile,
        Long subdivisionId,
        Long distributionCenterId,
        boolean enabled,
        boolean passwordResetRequired,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}

