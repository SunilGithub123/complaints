package com.example.complaints.auth.dto;

import com.example.complaints.auth.model.UserRole;

public record StaffSummaryResponse(
        Long id,
        String employeeId,
        String fullName,
        UserRole role,
        Long subdivisionId,
        Long distributionCenterId,
        boolean passwordResetRequired,
        boolean notificationsPushEnabled
) {}

