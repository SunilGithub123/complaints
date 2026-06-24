package com.example.complaints.masterdata.controller;

import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.masterdata.dto.ComplaintCategoryResponse;
import com.example.complaints.masterdata.dto.DistributionCenterResponse;
import com.example.complaints.masterdata.dto.SubdivisionResponse;
import com.example.complaints.masterdata.service.ComplaintCategoryService;
import com.example.complaints.masterdata.service.DistributionCenterService;
import com.example.complaints.masterdata.service.SubdivisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only master-data endpoints available to any authenticated staff member
 * (ADMIN / ENGINEER / TECHNICIAN). Writes live under {@code /api/v1/admin/masterdata/...}.
 */
@RestController
@RequestMapping("/api/v1/staff/masterdata")
@RequiredArgsConstructor
@Tag(name = "Master Data (read)", description = "Subdivisions, distribution centers, complaint categories")
public class MasterdataReadController {

    private final SubdivisionService subdivisions;
    private final DistributionCenterService dcs;
    private final ComplaintCategoryService categories;

    @GetMapping("/subdivisions")
    @Operation(operationId = "listSubdivisions",
            summary = "List subdivisions (paginated)")
    public ResponseEntity<ApiResponse<PageResponse<SubdivisionResponse>>> listSubdivisions(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(subdivisions.list(pageable)));
    }

    @Operation(operationId = "getSubdivision", summary = "Fetch a subdivision by id")
    @GetMapping("/subdivisions/{id}")
    public ResponseEntity<ApiResponse<SubdivisionResponse>> getSubdivision(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(subdivisions.get(id)));
    }

    @GetMapping("/distribution-centers")
    @Operation(operationId = "listDistributionCenters",
            summary = "List distribution centers (optionally filtered by subdivision)")
    public ResponseEntity<ApiResponse<PageResponse<DistributionCenterResponse>>> listDcs(
            @RequestParam(required = false) Long subdivisionId,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(dcs.list(subdivisionId, pageable)));
    }

    @Operation(operationId = "getDistributionCenter", summary = "Fetch a DC by id")
    @GetMapping("/distribution-centers/{id}")
    public ResponseEntity<ApiResponse<DistributionCenterResponse>> getDc(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(dcs.get(id)));
    }

    @GetMapping("/categories")
    @Operation(operationId = "listCategories",
            summary = "List complaint categories")
    public ResponseEntity<ApiResponse<PageResponse<ComplaintCategoryResponse>>> listCategories(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(categories.list(pageable)));
    }

    @Operation(operationId = "getCategory", summary = "Fetch a category by id")
    @GetMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<ComplaintCategoryResponse>> getCategory(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(categories.get(id)));
    }
}

