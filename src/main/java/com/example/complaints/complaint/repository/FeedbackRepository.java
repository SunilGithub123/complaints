package com.example.complaints.complaint.repository;

import com.example.complaints.complaint.model.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Stage 10b ships the repo only. Feedback writes / reads land in Phase 5
 * (see {@code ROADMAP.md} Phase 5).
 */
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    /**
     * Idempotency check for {@code POST /consumer/complaints/{ticketNo}/feedback}. The
     * underlying {@code UNIQUE (complaint_id)} constraint is the real safety net; this lookup
     * lets us return the friendlier {@code FEEDBACK_ALREADY_SUBMITTED} error code instead of
     * waiting for the DB constraint violation.
     */
    boolean existsByComplaintId(Long complaintId);

    /**
     * Read-back for the Stage 20.1 follow-up {@code GET /consumer/complaints/{ticketNo}/feedback}.
     * Returns the one feedback row for the complaint, if any.
     */
    Optional<Feedback> findByComplaintId(Long complaintId);

    /**
     * Batch existence lookup for the consumer tracking list (Stage 20.2). Returns the subset
     * of complaint IDs that already have a feedback row, so the service layer can mark
     * {@code feedbackSubmitted} on each {@link ConsumerComplaintListItemResponse} in a
     * single round-trip instead of {@code N} per-row probes. Empty input returns empty list
     * (callers should guard, but the query itself is safe).
     */
    @Query("select f.complaintId from Feedback f where f.complaintId in :ids")
    List<Long> findComplaintIdsWithFeedback(Collection<Long> ids);
}
