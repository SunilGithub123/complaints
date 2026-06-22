package com.example.complaints.complaint.repository;

import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ComplaintRepository
        extends JpaRepository<Complaint, Long>, JpaSpecificationExecutor<Complaint> {

    Optional<Complaint> findByTicketNo(String ticketNo);

    boolean existsByCategoryIdAndStatusIn(Long categoryId, Collection<ComplaintStatus> statuses);

    /**
     * Used by {@code SlaMonitorService} (Stage 15) to find complaints whose deadline has
     * elapsed but whose breach flag has not yet been flipped. Restricted to currently-open
     * statuses so that already-resolved/closed/cancelled rows are never touched again.
     */
    List<Complaint> findBySlaBreachedFalseAndStatusInAndSlaDeadlineBefore(
            Collection<ComplaintStatus> statuses, Instant now);
}

