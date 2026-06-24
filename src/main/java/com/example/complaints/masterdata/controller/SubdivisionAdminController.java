package com.example.complaints.masterdata.controller;

import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.masterdata.dto.SubdivisionRequest;
import com.example.complaints.masterdata.dto.SubdivisionResponse;
import com.example.complaints.masterdata.service.SubdivisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only writes. Mounted under {@code /api/v1/admin/**} so the SecurityConfig
 * {@code hasRole("ADMIN")} matcher gates the whole controller.
 */
@RestController
@RequestMapping("/api/v1/admin/masterdata/subdivisions")
@RequiredArgsConstructor
@Tag(name = "Master Data (admin) — Subdivisions")
public class SubdivisionAdminController {

    private final SubdivisionService service;

    @PostMapping
    @Operation(operationId = "createSubdivision",
            summary = "Create a new subdivision")
    public ResponseEntity<ApiResponse<SubdivisionResponse>> create(@Valid @RequestBody SubdivisionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.create(req)));
    }

    @PutMapping("/{id}")
    @Operation(operationId = "updateSubdivision",
            summary = "Update a subdivision")
    public ResponseEntity<ApiResponse<SubdivisionResponse>> update(
            @PathVariable Long id, @Valid @RequestBody SubdivisionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
    }

    @Operation(operationId = "deactivateSubdivision", summary = "Deactivate a subdivision")
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<SubdivisionResponse>> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(service.setActive(id, false)));
    }

    @Operation(operationId = "activateSubdivision", summary = "Activate a subdivision")
    @PostMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<SubdivisionResponse>> activate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(service.setActive(id, true)));
    }
}

