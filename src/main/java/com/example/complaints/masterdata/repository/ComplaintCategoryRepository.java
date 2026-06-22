package com.example.complaints.masterdata.repository;

import com.example.complaints.masterdata.model.ComplaintCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ComplaintCategoryRepository extends JpaRepository<ComplaintCategory, Long> {
    Optional<ComplaintCategory> findByCode(String code);
    boolean existsByCode(String code);
    Page<ComplaintCategory> findByActiveTrue(Pageable pageable);
}

