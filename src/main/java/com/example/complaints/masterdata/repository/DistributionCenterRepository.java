package com.example.complaints.masterdata.repository;

import com.example.complaints.masterdata.model.DistributionCenter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DistributionCenterRepository extends JpaRepository<DistributionCenter, Long> {
    Optional<DistributionCenter> findByCode(String code);
    boolean existsByCode(String code);
    Page<DistributionCenter> findBySubdivisionId(Long subdivisionId, Pageable pageable);
}

