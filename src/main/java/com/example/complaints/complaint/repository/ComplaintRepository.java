package com.example.complaints.complaint.repository;

import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    Optional<Complaint> findByTicketNo(String ticketNo);

    boolean existsByCategoryIdAndStatusIn(Long categoryId, Collection<ComplaintStatus> statuses);
}

