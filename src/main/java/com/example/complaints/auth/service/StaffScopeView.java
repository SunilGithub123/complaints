package com.example.complaints.auth.service;

import com.example.complaints.auth.model.UserRole;

/**
 * Minimal cross-module view of a staff row. Returned by {@link StaffLookupService} so other
 * modules can ask scope questions ("is this technician in my DC?", "who's the active engineer
 * for this DC?") without pulling in the full {@code UserAccount} entity or reaching into
 * {@code auth.repository} (forbidden by ArchUnit).
 */
public record StaffScopeView(
        Long userId,
        UserRole role,
        Long subdivisionId,
        Long distributionCenterId,
        boolean enabled
) {
}

