package com.example.complaints.masterdata.controller;

import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.masterdata.dto.ComplaintCategoryResponse;
import com.example.complaints.masterdata.service.ComplaintCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer-facing master-data reads. Gated by {@code ConsumerVerificationFilter} (the path is
 * under {@code /api/v1/consumer/**}) — every caller must hold a valid consumer verification JWT.
 *
 * <p>Scope is intentionally narrow: only the data the consumer submit form needs (active
 * complaint categories for the dropdown). Subdivisions and distribution centers are derived
 * server-side from {@code consumer_master} at submission, so we never expose them under
 * {@code /consumer/**}.</p>
 */
@RestController
@RequestMapping("/api/v1/consumer/masterdata")
@RequiredArgsConstructor
@Tag(name = "Consumer Master Data (read)", description = "Active master data for the consumer submit form")
public class ConsumerMasterdataReadController {

    private final ComplaintCategoryService categories;

    @GetMapping("/categories")
    @Operation(operationId = "listActiveCategoriesForConsumer",
            summary = "List active complaint categories for the consumer submit dropdown",
            description = "Returns only categories where active = true. Inactive rows are never "
                    + "surfaced to consumers.")
    public ResponseEntity<ApiResponse<PageResponse<ComplaintCategoryResponse>>> listActiveCategories(
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(categories.listActive(pageable)));
    }
}

