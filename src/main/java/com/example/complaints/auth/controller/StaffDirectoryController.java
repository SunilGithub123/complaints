package com.example.complaints.auth.controller;

import com.example.complaints.auth.dto.StaffDirectoryEntryResponse;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.service.StaffLookupService;
import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Staff directory read endpoints (Stage 14.5). Any authenticated staff member can resolve
 * another staff member's name + role via id — used by the FE complaint history timeline,
 * technician picker dropdowns, and (Stage 16) the engineer/technician columns in the
 * paged complaints list.
 *
 * <p>Path-level gate is the existing {@code /api/v1/staff/**} → {@code .authenticated()}
 * matcher in {@code SecurityConfig}; no role split — engineers, admins and technicians may
 * all read directory entries.</p>
 *
 * <p>Personal flags ({@code passwordResetRequired}, {@code notificationsPushEnabled}) are
 * deliberately not on the {@link StaffDirectoryEntryResponse} shape; for the caller's own
 * profile use {@code GET /api/v1/staff/auth/me}.</p>
 */
@RestController
@RequestMapping("/api/v1/staff/users")
@RequiredArgsConstructor
@Validated
@Tag(name = "Staff Directory", description = "Resolve staff user ids to names + roles")
public class StaffDirectoryController {

    /** Hard cap on batch ids per call — keeps the request URL bounded. */
    private static final int MAX_BATCH_IDS = 50;

    private final StaffLookupService lookup;

    @GetMapping("/{id}")
    @Operation(summary = "Resolve a single staff id to a directory entry")
    public ResponseEntity<ApiResponse<StaffDirectoryEntryResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(lookup.getDirectoryEntry(id)));
    }

    @GetMapping(params = "ids")
    @Operation(summary = "Batch-resolve up to 50 staff ids in one round-trip",
            description = "Unknown ids are silently dropped. Order of the response is not guaranteed.")
    public ResponseEntity<ApiResponse<List<StaffDirectoryEntryResponse>>> getMany(
            @RequestParam("ids") @Size(min = 1, max = MAX_BATCH_IDS) List<Long> ids
    ) {
        return ResponseEntity.ok(ApiResponse.ok(lookup.getDirectoryEntries(ids)));
    }

    @GetMapping
    @Operation(
            summary = "Paged directory search (filterable by role / DC / active)",
            description = "Used by the FE picker dropdowns. Scope is server-enforced: "
                    + "engineers / technicians are pinned to their own DC; admins to their subdivision. "
                    + "A non-admin caller asking for a different DC gets 403 FORBIDDEN."
    )
    public ResponseEntity<ApiResponse<PageResponse<StaffDirectoryEntryResponse>>> search(
            @AuthenticationPrincipal AuthenticatedStaff caller,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) Long distributionCenterId,
            @RequestParam(required = false) Boolean active,
            // Default sort fullName,asc so picker dropdowns are alphabetical without the FE
            // having to remember to pass ?sort=fullName,asc on every call.
            @PageableDefault(size = 20, sort = "fullName", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                lookup.searchDirectory(caller, role, distributionCenterId, active, pageable)));
    }
}

