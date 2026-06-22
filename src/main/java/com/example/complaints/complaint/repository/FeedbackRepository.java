package com.example.complaints.complaint.repository;

import com.example.complaints.complaint.model.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Stage 10b ships the repo only. Feedback writes / reads land in Phase 5
 * (see {@code ROADMAP.md} Phase 5).
 */
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
}

