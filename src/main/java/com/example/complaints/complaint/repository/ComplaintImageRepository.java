package com.example.complaints.complaint.repository;

import com.example.complaints.complaint.model.ComplaintImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComplaintImageRepository extends JpaRepository<ComplaintImage, Long> {

    List<ComplaintImage> findByComplaintIdOrderByIdAsc(Long complaintId);
}

