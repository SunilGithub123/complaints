package com.example.complaints.complaint.mapper;

import com.example.complaints.common.util.DateUtils;
import com.example.complaints.complaint.dto.ComplaintDetailResponse;
import com.example.complaints.complaint.dto.ComplaintHistoryEntryResponse;
import com.example.complaints.complaint.dto.ComplaintImageResponse;
import com.example.complaints.complaint.dto.ComplaintStaffDetailResponse;
import com.example.complaints.complaint.dto.SubmitComplaintResponse;
import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.model.ComplaintImage;
import com.example.complaints.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Hand-written entity ↔ DTO mapper. Image URLs are minted via {@link StorageService#signedReadUrl}
 * at mapping time so the response always carries fresh short-lived URLs.
 */
@Component
@RequiredArgsConstructor
public class ComplaintMapper {

    private static final Duration IMAGE_URL_TTL = Duration.ofMinutes(15);

    private final StorageService storage;

    public SubmitComplaintResponse toSubmitResponse(Complaint c, List<ComplaintImage> images) {
        return new SubmitComplaintResponse(
                c.getId(),
                c.getTicketNo(),
                c.getStatus(),
                DateUtils.toIst(c.getCreatedAt()),
                DateUtils.toIst(c.getSlaDeadline()),
                images.stream().map(this::toImageResponse).toList()
        );
    }

    public ComplaintDetailResponse toDetailResponse(Complaint c, String consumerId, List<ComplaintImage> images) {
        return new ComplaintDetailResponse(
                c.getId(),
                c.getTicketNo(),
                consumerId,
                c.getContactMobile(),
                c.getCategoryId(),
                c.getDescription(),
                c.getLocation(),
                c.getStatus(),
                DateUtils.toIst(c.getCreatedAt()),
                DateUtils.toIst(c.getSlaDeadline()),
                images.stream().map(this::toImageResponse).toList()
        );
    }

    private ComplaintImageResponse toImageResponse(ComplaintImage img) {
        return new ComplaintImageResponse(
                img.getId(),
                img.getContentType(),
                img.getSizeBytes(),
                storage.signedReadUrl(img.getStorageKey(), IMAGE_URL_TTL),
                DateUtils.toIst(img.getCreatedAt())
        );
    }

    public ComplaintStaffDetailResponse toStaffDetailResponse(Complaint c, List<ComplaintImage> images) {
        return new ComplaintStaffDetailResponse(
                c.getId(),
                c.getTicketNo(),
                c.getConsumerMasterId(),
                c.getContactMobile(),
                c.getCategoryId(),
                c.getSeverity(),
                c.getDescription(),
                c.getLocation(),
                c.getDistributionCenterId(),
                c.getAssignedEngineerId(),
                c.getAssignedTechnicianId(),
                c.getParentComplaintId(),
                c.getStatus(),
                c.isSlaBreached(),
                c.getResolutionNotes(),
                c.getSlaBreachReason(),
                c.getCancellationReason(),
                c.getRejectionReason(),
                DateUtils.toIst(c.getCreatedAt()),
                DateUtils.toIst(c.getUpdatedAt()),
                DateUtils.toIst(c.getSlaDeadline()),
                DateUtils.toIst(c.getResolvedAt()),
                DateUtils.toIst(c.getClosedAt()),
                c.getVersion(),
                images.stream().map(this::toImageResponse).toList()
        );
    }

    public ComplaintHistoryEntryResponse toHistoryResponse(ComplaintHistory h) {
        return new ComplaintHistoryEntryResponse(
                h.getId(),
                h.getFromStatus(),
                h.getToStatus(),
                h.getChangedByUserId(),
                h.getNote(),
                DateUtils.toIst(h.getChangedAt())
        );
    }
}

