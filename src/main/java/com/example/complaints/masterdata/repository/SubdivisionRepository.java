package com.example.complaints.masterdata.repository;

import com.example.complaints.masterdata.model.Subdivision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubdivisionRepository extends JpaRepository<Subdivision, Long> {
    Optional<Subdivision> findByCode(String code);
    boolean existsByCode(String code);
}

