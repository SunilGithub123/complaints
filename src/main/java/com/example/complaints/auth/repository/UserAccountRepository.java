package com.example.complaints.auth.repository;

import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmployeeId(String employeeId);

    boolean existsByEmployeeId(String employeeId);

    boolean existsByRoleAndEnabledTrue(UserRole role);

    boolean existsByRoleAndSubdivisionIdAndEnabledTrue(UserRole role, Long subdivisionId);

    boolean existsByRoleAndDistributionCenterIdAndEnabledTrue(UserRole role, Long distributionCenterId);

    /** True iff at least one enabled staff row references this subdivision (any role). */
    boolean existsBySubdivisionIdAndEnabledTrue(Long subdivisionId);

    /** True iff at least one enabled staff row references this DC (any role). */
    boolean existsByDistributionCenterIdAndEnabledTrue(Long distributionCenterId);

    Optional<UserAccount> findFirstByRoleAndDistributionCenterIdAndEnabledTrue(UserRole role, Long distributionCenterId);

    /**
     * Paged staff search scoped to a single subdivision. {@code role}, {@code distributionCenterId},
     * and {@code enabled} are all optional filters — pass {@code null} to skip.
     */
    @Query("""
            SELECT u FROM UserAccount u
            WHERE u.subdivisionId = :subdivisionId
              AND (:role IS NULL OR u.role = :role)
              AND (:distributionCenterId IS NULL OR u.distributionCenterId = :distributionCenterId)
              AND (:enabled IS NULL OR u.enabled = :enabled)
            """)
    Page<UserAccount> search(
            @Param("subdivisionId") Long subdivisionId,
            @Param("role") UserRole role,
            @Param("distributionCenterId") Long distributionCenterId,
            @Param("enabled") Boolean enabled,
            Pageable pageable);
}

