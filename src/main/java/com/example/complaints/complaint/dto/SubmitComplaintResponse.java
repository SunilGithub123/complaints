package com.example.complaints.complaint.dto;

import com.example.complaints.complaint.model.ComplaintStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response body for {@code POST /api/v1/consumer/complaints}. Returned with HTTP 201.
 *
 * <p>Carries everything the FE needs to render the confirmation screen without a second
 * round-trip: the ticket number (for the copy / share affordance), the status, the IST
 * submission timestamp, the SLA deadline, and the persisted image refs.</p>
 */
public record SubmitComplaintResponse(
        Long complaintId,
        String ticketNo,
        ComplaintStatus status,
        OffsetDateTime submittedAt,
        OffsetDateTime slaDeadline,
        List<ComplaintImageResponse> images
) {
}

