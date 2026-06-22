package com.example.complaints.complaint.controller;

import com.example.complaints.auth.security.VerifiedConsumer;
import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.complaint.dto.ComplaintDetailResponse;
import com.example.complaints.complaint.dto.SubmitComplaintRequest;
import com.example.complaints.complaint.dto.SubmitComplaintResponse;
import com.example.complaints.complaint.service.ComplaintCreationService;
import com.example.complaints.complaint.service.ComplaintReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Consumer-facing complaint endpoints. Gated by {@code ConsumerVerificationFilter}; the
 * {@link VerifiedConsumer} principal is injected via {@code @AuthenticationPrincipal}.
 */
@RestController
@RequestMapping("/api/v1/consumer/complaints")
@RequiredArgsConstructor
@Tag(name = "Consumer Complaints", description = "Submit and read back consumer-raised complaints")
public class ConsumerComplaintController {

    private final ComplaintCreationService creation;
    private final ComplaintReadService read;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Submit a new complaint with up to 3 images in a single multipart request",
            description = "Multipart contract: one JSON part named `complaint` (SubmitComplaintRequest) "
                    + "plus 0..3 image parts named `images` (image/jpeg or image/png, each ≤ 1 MB). "
                    + "Ticket number, SLA deadline and signed image URLs are returned in one response — "
                    + "no follow-up calls required for the confirmation screen.",
            requestBody = @RequestBody(content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    schema = @Schema(implementation = SubmitMultipartForm.class),
                    encoding = {
                            @Encoding(name = "complaint", contentType = MediaType.APPLICATION_JSON_VALUE),
                            @Encoding(name = "images",    contentType = "image/jpeg, image/png")
                    }))
    )
    public ResponseEntity<ApiResponse<SubmitComplaintResponse>> submit(
            @AuthenticationPrincipal VerifiedConsumer caller,
            @Valid @RequestPart("complaint") SubmitComplaintRequest complaint,
            @RequestPart(value = "images", required = false) List<MultipartFile> images
    ) {
        SubmitComplaintResponse body = creation.submit(caller, complaint, images);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @GetMapping("/{ticketNo}")
    @Operation(
            summary = "Fetch the verified consumer's own complaint by ticket number",
            description = "Stage 10b scope: confirmation / refresh-safe read of a just-submitted "
                    + "complaint. Lifecycle history, technician identity and feedback land in Phase 5."
    )
    public ResponseEntity<ApiResponse<ComplaintDetailResponse>> getByTicket(
            @AuthenticationPrincipal VerifiedConsumer caller,
            @PathVariable String ticketNo
    ) {
        return ResponseEntity.ok(ApiResponse.ok(read.getOwnedByTicketNo(caller, ticketNo)));
    }

    /**
     * Documentation-only multipart shape. springdoc otherwise can't express the form-part layout
     * for OpenAPI consumers (the codegen relies on this to render the form schema).
     */
    @Schema(name = "SubmitMultipartForm",
            description = "multipart/form-data layout for POST /api/v1/consumer/complaints")
    private record SubmitMultipartForm(
            @Schema(implementation = SubmitComplaintRequest.class) SubmitComplaintRequest complaint,
            @Schema(type = "array", description = "0..3 image parts (image/jpeg or image/png)")
            List<MultipartFile> images
    ) {
    }
}

