package com.example.complaints.masterdata.controller;

import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.masterdata.dto.DistributionCenterRequest;
import com.example.complaints.masterdata.dto.DistributionCenterResponse;
import com.example.complaints.masterdata.service.DistributionCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/masterdata/distribution-centers")
@RequiredArgsConstructor
@Tag(name = "Master Data (admin) — Distribution Centers")
public class DistributionCenterAdminController {

    private final DistributionCenterService service;

    @PostMapping
    @Operation(summary = "Create a DC under the admin's subdivision")
    public ResponseEntity<ApiResponse<DistributionCenterResponse>> create(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @Valid @RequestBody DistributionCenterRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.create(me, req)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DistributionCenterResponse>> update(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @PathVariable Long id,
            @Valid @RequestBody DistributionCenterRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(me, id, req)));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<DistributionCenterResponse>> deactivate(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(service.setActive(me, id, false)));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<DistributionCenterResponse>> activate(
            @AuthenticationPrincipal AuthenticatedStaff me,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(service.setActive(me, id, true)));
    }
}

