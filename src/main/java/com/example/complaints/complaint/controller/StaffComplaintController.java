package com.example.complaints.complaint.controller;

import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.complaint.dto.AssignComplaintRequest;
import com.example.complaints.complaint.dto.CloseComplaintRequest;
import com.example.complaints.complaint.dto.ComplaintHistoryEntryResponse;
import com.example.complaints.complaint.dto.ComplaintListItemResponse;
import com.example.complaints.complaint.dto.ComplaintSearchRequest;
import com.example.complaints.complaint.dto.ComplaintStaffDetailResponse;
import com.example.complaints.complaint.dto.MarkDuplicateRequest;
import com.example.complaints.complaint.dto.ReassignComplaintRequest;
import com.example.complaints.complaint.dto.RejectComplaintRequest;
import com.example.complaints.complaint.dto.UpdateSeverityRequest;
import com.example.complaints.complaint.service.ComplaintAssignmentService;
import com.example.complaints.complaint.service.ComplaintClosureService;
import com.example.complaints.complaint.service.ComplaintSearchService;
import com.example.complaints.complaint.service.ComplaintStaffReadService;
import com.example.complaints.complaint.service.ComplaintTriageService;
import com.example.complaints.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Engineer / Admin complaint-management endpoints (Phase 4 Stage 13). See TECHNICAL_DESIGN.md §5.4.
 *
 * <p>Path-level role gate is enforced by {@code SecurityConfig} matcher
 * {@code /api/v1/staff/complaints/**} → {@code hasAnyRole("ENGINEER","ADMIN")}, so technicians
 * never reach this controller. Per-action scope (engineer DC / admin subdivision) is enforced
 * inside the services via {@code ComplaintScopeGuard}.</p>
 *
 * <p>List ({@code GET}) and search ship in Stage 16 (Specifications). Close-on-behalf ships in
 * Stage 14 alongside technician resolution.</p>
 */
@RestController
@RequestMapping("/api/v1/staff/complaints")
@RequiredArgsConstructor
@Tag(name = "Staff Complaint Management",
        description = "Engineer / Admin actions on complaints: assign, reassign, severity, reject, mark-duplicate")
public class StaffComplaintController {

    private final ComplaintAssignmentService assignment;
    private final ComplaintTriageService triage;
    private final ComplaintStaffReadService read;
    private final ComplaintClosureService closure;
    private final ComplaintSearchService search;

    @GetMapping
    @Operation(summary = "Paged complaint search for engineer / admin",
            description = "Filters compose with caller-scope: engineer = own DC, admin = own subdivision. "
                    + "Default sort is createdAt,desc.")
    public ResponseEntity<ApiResponse<PageResponse<ComplaintListItemResponse>>> list(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            ComplaintSearchRequest filters,
            Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.ok(search.listForStaff(caller, filters, pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Engineer / Admin detail view of a complaint (scope-checked)")
    public ResponseEntity<ApiResponse<ComplaintStaffDetailResponse>> getById(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(ApiResponse.ok(read.getById(caller, id)));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Status-change audit trail for a complaint (chronological)")
    public ResponseEntity<ApiResponse<List<ComplaintHistoryEntryResponse>>> getHistory(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(ApiResponse.ok(read.getHistory(caller, id)));
    }

    @PostMapping("/{id}/assign")
    @Operation(summary = "Assign a SUBMITTED complaint to a technician and set severity")
    public ResponseEntity<ApiResponse<Void>> assign(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable Long id,
            @Valid @RequestBody AssignComplaintRequest req
    ) {
        assignment.assign(caller, id, req);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{id}/reassign")
    @Operation(summary = "Reassign an already-assigned complaint to a different technician")
    public ResponseEntity<ApiResponse<Void>> reassign(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable Long id,
            @Valid @RequestBody ReassignComplaintRequest req
    ) {
        assignment.reassign(caller, id, req);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{id}/severity")
    @Operation(summary = "Update severity of a non-terminal complaint")
    public ResponseEntity<ApiResponse<Void>> updateSeverity(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable Long id,
            @Valid @RequestBody UpdateSeverityRequest req
    ) {
        triage.updateSeverity(caller, id, req);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a SUBMITTED complaint with a reason")
    public ResponseEntity<ApiResponse<Void>> reject(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable Long id,
            @Valid @RequestBody RejectComplaintRequest req
    ) {
        triage.reject(caller, id, req);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{id}/mark-duplicate")
    @Operation(summary = "Mark a SUBMITTED complaint as a duplicate of another (by ticket number)")
    public ResponseEntity<ApiResponse<Void>> markDuplicate(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable Long id,
            @Valid @RequestBody MarkDuplicateRequest req
    ) {
        triage.markDuplicate(caller, id, req);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Engineer / Admin close-on-behalf of a RESOLVED complaint")
    public ResponseEntity<ApiResponse<Void>> close(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable Long id,
            @Valid @RequestBody CloseComplaintRequest req
    ) {
        closure.close(caller, id, req);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}

