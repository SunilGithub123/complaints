package com.example.complaints.masterdata.controller;

import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.masterdata.dto.ComplaintCategoryRequest;
import com.example.complaints.masterdata.dto.ComplaintCategoryResponse;
import com.example.complaints.masterdata.service.ComplaintCategoryService;
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

@RestController
@RequestMapping("/api/v1/admin/masterdata/categories")
@RequiredArgsConstructor
@Tag(name = "Master Data (admin) — Categories")
public class ComplaintCategoryAdminController {

    private final ComplaintCategoryService service;

    @PostMapping
    @Operation(summary = "Create a complaint category")
    public ResponseEntity<ApiResponse<ComplaintCategoryResponse>> create(
            @Valid @RequestBody ComplaintCategoryRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.create(req)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ComplaintCategoryResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody ComplaintCategoryRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<ComplaintCategoryResponse>> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(service.setActive(id, false)));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<ComplaintCategoryResponse>> activate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(service.setActive(id, true)));
    }
}

