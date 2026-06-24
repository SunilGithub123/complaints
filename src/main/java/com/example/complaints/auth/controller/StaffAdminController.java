package com.example.complaints.auth.controller;

import com.example.complaints.auth.dto.CreateStaffRequest;
import com.example.complaints.auth.dto.ResetStaffPasswordResponse;
import com.example.complaints.auth.dto.StaffListItemResponse;
import com.example.complaints.auth.dto.UpdateStaffRequest;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.auth.service.StaffAdminService;
import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → staff user management. All routes here are auto-scoped to the
 * caller's subdivision; cross-subdivision attempts are rejected by
 * {@link StaffAdminService}.
 */
@Tag(name = "Admin - Staff")
@RestController
@RequestMapping("/api/v1/admin/staff")
@RequiredArgsConstructor
public class StaffAdminController {

    private final StaffAdminService service;

    @Operation(operationId = "listStaff",
            summary = "List staff in the admin's subdivision (paged, filterable)")
    @GetMapping
    public ApiResponse<PageResponse<StaffListItemResponse>> list(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) Long distributionCenterId,
            @RequestParam(required = false) Boolean enabled,
            Pageable pageable) {
        return ApiResponse.ok(service.list(me, role, distributionCenterId, enabled, pageable));
    }

    @Operation(operationId = "getStaff",
            summary = "Fetch one staff account by ID")
    @GetMapping("/{id}")
    public ApiResponse<StaffListItemResponse> get(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @PathVariable Long id) {
        return ApiResponse.ok(service.get(me, id));
    }

    @Operation(operationId = "createStaff",
            summary = "Create a staff account; returns the one-time temporary password")
    @PostMapping
    public ApiResponse<ResetStaffPasswordResponse> create(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @Valid @RequestBody CreateStaffRequest body) {
        return ApiResponse.ok(service.create(me, body));
    }

    @Operation(operationId = "updateStaff",
            summary = "Update an existing staff account (profile + DC reassignment)")
    @PutMapping("/{id}")
    public ApiResponse<StaffListItemResponse> update(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @PathVariable Long id,
            @Valid @RequestBody UpdateStaffRequest body) {
        return ApiResponse.ok(service.update(me, id, body));
    }

    @Operation(operationId = "activateStaff",
            summary = "Activate a staff account")
    @PostMapping("/{id}/activate")
    public ApiResponse<StaffListItemResponse> activate(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @PathVariable Long id) {
        return ApiResponse.ok(service.setActive(me, id, true));
    }

    @Operation(operationId = "deactivateStaff",
            summary = "Deactivate a staff account (revokes all live sessions)")
    @PostMapping("/{id}/deactivate")
    public ApiResponse<StaffListItemResponse> deactivate(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @PathVariable Long id) {
        return ApiResponse.ok(service.setActive(me, id, false));
    }

    @Operation(operationId = "resetStaffPassword",
            summary = "Reset a staff account password; returns the one-time temporary password")
    @PostMapping("/{id}/reset-password")
    public ApiResponse<ResetStaffPasswordResponse> resetPassword(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @PathVariable Long id) {
        return ApiResponse.ok(service.resetPassword(me, id));
    }
}



