package com.example.complaints.auth.repository;

import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.masterdata.model.DistributionCenter;
import com.example.complaints.masterdata.model.Subdivision;
import com.example.complaints.masterdata.repository.DistributionCenterRepository;
import com.example.complaints.masterdata.repository.SubdivisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Slice IT covering {@link UserAccountRepository#search} — the first non-derived query in the
 * codebase (Stage 5 follow-up). Runs against a real Postgres via Testcontainers because the
 * embedded H2 default would diverge on JPQL {@code IS NULL} short-circuits and on Postgres-specific
 * column defaults applied by Flyway in {@code V1.0__init_schema.sql}.
 *
 * <p>Per the minimum-test policy: 1 representative happy-path query + 1 representative
 * filter-application — enough to catch any future query refactor that drops a predicate.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class UserAccountRepositoryIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("complaints")
            .withUsername("complaints")
            .withPassword("complaints");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired UserAccountRepository repo;
    @Autowired SubdivisionRepository subdivisions;
    @Autowired DistributionCenterRepository dcs;

    private Long subA;
    private Long subB;
    private Long dcA1;
    private Long dcA2;

    @BeforeEach
    void seed() {
        repo.deleteAll();
        dcs.deleteAll();
        subdivisions.deleteAll();

        subA = subdivisions.save(Subdivision.builder()
                .code("SUB-IT-A").name("A").district("D").active(true).build()).getId();
        subB = subdivisions.save(Subdivision.builder()
                .code("SUB-IT-B").name("B").district("D").active(true).build()).getId();
        dcA1 = dcs.save(DistributionCenter.builder()
                .subdivisionId(subA).code("DC-IT-A1").name("A1").active(true).build()).getId();
        dcA2 = dcs.save(DistributionCenter.builder()
                .subdivisionId(subA).code("DC-IT-A2").name("A2").active(true).build()).getId();

        repo.save(staff("ADM-A",   UserRole.ADMIN,      subA, null, true));
        repo.save(staff("ENG-A1",  UserRole.ENGINEER,   subA, dcA1, true));
        repo.save(staff("TEC-A1a", UserRole.TECHNICIAN, subA, dcA1, true));
        repo.save(staff("TEC-A1b", UserRole.TECHNICIAN, subA, dcA1, false)); // disabled
        repo.save(staff("ENG-A2",  UserRole.ENGINEER,   subA, dcA2, true));
        repo.save(staff("ADM-B",   UserRole.ADMIN,      subB, null, true));  // out of scope
    }

    @Test
    @DisplayName("search: scopes to subdivision and skips null filters")
    void search_subdivisionScopeOnly() {
        Page<UserAccount> result = repo.search(subA, null, null, null, PageRequest.of(0, 20));

        assertThat(result.getContent())
                .extracting(UserAccount::getEmployeeId)
                .containsExactlyInAnyOrder("ADM-A", "ENG-A1", "TEC-A1a", "TEC-A1b", "ENG-A2");
    }

    @Test
    @DisplayName("search: combined role + DC + enabled filters narrow the result correctly")
    void search_allFiltersApplied() {
        Page<UserAccount> result = repo.search(
                subA, UserRole.TECHNICIAN, dcA1, Boolean.TRUE, PageRequest.of(0, 20));

        assertThat(result.getContent())
                .extracting(UserAccount::getEmployeeId)
                .containsExactly("TEC-A1a");
    }

    private static UserAccount staff(String empId, UserRole role, Long subId, Long dcId, boolean enabled) {
        return UserAccount.builder()
                .employeeId(empId)
                .passwordHash("x")
                .passwordResetRequired(false)
                .role(role)
                .fullName(empId)
                .subdivisionId(subId)
                .distributionCenterId(dcId)
                .enabled(enabled)
                .notificationsPushEnabled(true)
                .build();
    }
}




