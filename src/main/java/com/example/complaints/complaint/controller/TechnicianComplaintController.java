package com.example.complaints.complaint.controller;

import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.complaint.dto.CloseComplaintRequest;
import com.example.complaints.complaint.dto.ComplaintImageResponse;
import com.example.complaints.complaint.dto.ComplaintListItemResponse;
import com.example.complaints.complaint.dto.ComplaintSearchRequest;
import com.example.complaints.complaint.dto.ResolveComplaintRequest;
import com.example.complaints.complaint.service.ComplaintClosureService;
import com.example.complaints.complaint.service.ComplaintResolutionService;
import com.example.complaints.complaint.service.ComplaintSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Technician-facing complaint endpoints (Phase 4 Stage 14). Path-level role gate
 * {@code /api/v1/technician/**} → {@code hasRole("TECHNICIAN")} is in SecurityConfig.
 * Per-complaint scope (must be {@code assigned_technician_id == me}) is enforced inside
 * {@link ComplaintResolutionService}.
 */
@RestController
@RequestMapping("/api/v1/technician/complaints")
@RequiredArgsConstructor
@Tag(name = "Technician Complaints",
        description = "Technician lifecycle actions: start, resolve, add resolution images, close")
public class TechnicianComplaintController {

    private final ComplaintResolutionService resolution;
    private final ComplaintClosureService closure;
    private final ComplaintSearchService search;

    @GetMapping
    @Operation(operationId = "listTechnicianComplaints",
            summary = "Paged list of complaints assigned to the calling technician",
            description = "Server pins assigned_technician_id = caller.userId(). "
                    + "Optional filters: status, severity, slaBreached, dateFrom/dateTo, q.")
    public ResponseEntity<ApiResponse<PageResponse<ComplaintListItemResponse>>> list(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            ComplaintSearchRequest filters,
            Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.ok(search.listForTechnician(caller, filters, pageable)));
    }

    @PostMapping("/{id}/start")
    @Operation(operationId = "startComplaint",
            summary = "Start work on an ASSIGNED complaint (→ IN_PROGRESS)")
    public ResponseEntity<ApiResponse<Void>> start(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable Long id
    ) {
        resolution.start(caller, id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/{id}/resolve")
    @Operation(operationId = "resolveComplaint",
            summary = "Mark an IN_PROGRESS complaint RESOLVED (SLA-breach reason required if late)")
    public ResponseEntity<ApiResponse<Void>> resolve(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable Long id,
            @Valid @RequestBody ResolveComplaintRequest req
    ) {
        resolution.resolve(caller, id, req);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "addResolutionImages",
            summary = "Upload up to 3 resolution images (multipart, image/jpeg or image/png)")
    public ResponseEntity<ApiResponse<List<ComplaintImageResponse>>> addImages(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable Long id,
            @RequestPart("images") List<MultipartFile> images
    ) {
        return ResponseEntity.ok(ApiResponse.ok(resolution.addResolutionImages(caller, id, images)));
    }

    @PostMapping("/{id}/close")
    @Operation(operationId = "closeComplaintAsTechnician",
            summary = "Close a RESOLVED complaint that is assigned to the calling technician",
            description = "BRD §4.8: technician is the normal closing actor. Caller must be the "
                    + "assigned technician; complaint must be RESOLVED. Body's slaBreachReason is "
                    + "required only when the complaint is SLA-breached and no reason was already "
                    + "captured at resolve time.")
    public ResponseEntity<ApiResponse<Void>> close(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @PathVariable Long id,
            @Valid @RequestBody CloseComplaintRequest req
    ) {
        closure.closeByTechnician(caller, id, req);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}

