package com.example.complaints.auth.dto;

import com.example.complaints.auth.model.UserRole;

/**
 * Minimal directory entry returned by {@code GET /api/v1/staff/users/{id}} and
 * {@code GET /api/v1/staff/users?ids=...}. Used by the staff UI to resolve user IDs
 * (history-row actors, technician/engineer assignment columns, picker dropdowns) into
 * human-readable names.
 *
 * <p>Intentionally narrower than {@link StaffSummaryResponse} (the "me" shape): does not
 * expose {@code passwordResetRequired} or {@code notificationsPushEnabled}, which are
 * personal flags no other staff member should see. Scope IDs are included so client-side
 * pickers can filter without an extra round-trip.</p>
 */
public record StaffDirectoryEntryResponse(
        Long userId,
        String employeeId,
        String fullName,
        UserRole role,
        Long subdivisionId,
        Long distributionCenterId,
        boolean enabled
) {}

