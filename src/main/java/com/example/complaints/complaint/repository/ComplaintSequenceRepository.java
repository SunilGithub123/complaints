package com.example.complaints.complaint.repository;

import com.example.complaints.complaint.model.ComplaintSequence;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring-Data wrapper for {@link ComplaintSequence}. {@code TicketNumberService} bypasses derived
 * finders and uses {@code EntityManager} for the native upsert + advisory-lock SQL — this
 * interface exists for schema integration and for read-only test setup / assertions.
 */
public interface ComplaintSequenceRepository extends JpaRepository<ComplaintSequence, String> {
}

