package com.example.complaints.auth.repository;

import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmployeeId(String employeeId);

    boolean existsByEmployeeId(String employeeId);

    boolean existsByRoleAndEnabledTrue(UserRole role);

    boolean existsByRoleAndSubdivisionIdAndEnabledTrue(UserRole role, Long subdivisionId);

    boolean existsByRoleAndDistributionCenterIdAndEnabledTrue(UserRole role, Long distributionCenterId);
}

