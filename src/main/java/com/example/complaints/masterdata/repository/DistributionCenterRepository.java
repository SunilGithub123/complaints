package com.example.complaints.masterdata.repository;

import com.example.complaints.masterdata.model.DistributionCenter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DistributionCenterRepository extends JpaRepository<DistributionCenter, Long> {
    Optional<DistributionCenter> findByCode(String code);
    boolean existsByCode(String code);
    Page<DistributionCenter> findBySubdivisionId(Long subdivisionId, Pageable pageable);

    /** Used by {@code SubdivisionService.setActive(false)} to block deactivation while children are live. */
    boolean existsBySubdivisionIdAndActiveTrue(Long subdivisionId);

    /** Lightweight id-projection used by complaint-list admin-scope filtering (Stage 16). */
    @Query("SELECT d.id FROM DistributionCenter d WHERE d.subdivisionId = :subdivisionId")
    List<Long> findIdsBySubdivisionId(Long subdivisionId);
}

