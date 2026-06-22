package com.example.complaints.complaint.dto;

import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.complaint.model.ComplaintStatus;

import java.time.Instant;

/**
 * Bound from query parameters by Spring MVC for {@code GET /api/v1/staff/complaints}
 * and {@code GET /api/v1/technician/complaints}. All filters are optional and compose
 * with caller-scope (engineer DC / admin subdivision / technician self) which is
 * applied server-side before any user-supplied filter.
 *
 * <p>{@code distributionCenterId} is honoured only for ADMIN callers; for engineer /
 * technician callers it's ignored or rejected (see service Javadoc).</p>
 */
public record ComplaintSearchRequest(
        ComplaintStatus status,
        ComplaintSeverity severity,
        Long categoryId,
        Long distributionCenterId,
        Long assignedTechnicianId,
        Boolean slaBreached,
        Instant dateFrom,
        Instant dateTo,
        String q
) {
}

