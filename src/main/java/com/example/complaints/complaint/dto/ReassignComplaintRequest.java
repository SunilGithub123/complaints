package com.example.complaints.complaint.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Engineer/Admin request: hand an already-assigned complaint to a different technician
 * (TECHNICAL_DESIGN.md §5.4). Status is not changed by this operation.
 *
 * <ul>
 *   <li><b>Engineer</b> caller — new technician must be in the same DC as the complaint.</li>
 *   <li><b>Admin</b> caller — may pick any technician within the admin's subdivision; the
 *       complaint's {@code distribution_center_id} and {@code assigned_engineer_id} are
 *       re-pointed to the new DC's active engineer.</li>
 * </ul>
 */
public record ReassignComplaintRequest(
        @NotNull Long technicianId,
        @Size(max = 500) String reason
) {
}

