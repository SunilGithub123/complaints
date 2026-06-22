package com.example.complaints.auth.service;

import com.example.complaints.auth.dto.StaffDirectoryEntryResponse;
import com.example.complaints.auth.model.UserAccount;
import com.example.complaints.auth.model.UserRole;
import com.example.complaints.auth.repository.UserAccountRepository;
import com.example.complaints.auth.security.AuthenticatedStaff;
import com.example.complaints.common.dto.PageResponse;
import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaffLookupServiceDirectoryTest {

    private UserAccountRepository users;
    private StaffLookupService service;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        service = new StaffLookupService(users);
    }

    @Test
    @DisplayName("getDirectoryEntry: returns a minimal directory entry (no personal flags)")
    void getDirectoryEntry_happyPath() {
        when(users.findById(42L)).thenReturn(Optional.of(staff(42L, "ENG-007", UserRole.ENGINEER)));

        StaffDirectoryEntryResponse entry = service.getDirectoryEntry(42L);

        assertThat(entry.userId()).isEqualTo(42L);
        assertThat(entry.employeeId()).isEqualTo("ENG-007");
        assertThat(entry.fullName()).isEqualTo("Test Engineer");
        assertThat(entry.role()).isEqualTo(UserRole.ENGINEER);
        assertThat(entry.enabled()).isTrue();
    }

    @Test
    @DisplayName("getDirectoryEntry: unknown id → STAFF_NOT_FOUND")
    void getDirectoryEntry_notFound() {
        when(users.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDirectoryEntry(42L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STAFF_NOT_FOUND);
    }

    @Test
    @DisplayName("getDirectoryEntries: batch drops unknown ids silently")
    void getDirectoryEntries_dropsUnknownIds() {
        when(users.findAllById(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(staff(1L, "A", UserRole.ENGINEER), staff(3L, "C", UserRole.TECHNICIAN)));

        List<StaffDirectoryEntryResponse> out = service.getDirectoryEntries(List.of(1L, 2L, 3L));

        assertThat(out).extracting(StaffDirectoryEntryResponse::userId).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    @DisplayName("getDirectoryEntries: empty / null input returns empty list without a DB hit")
    void getDirectoryEntries_emptyInput() {
        assertThat(service.getDirectoryEntries(List.of())).isEmpty();
        assertThat(service.getDirectoryEntries(null)).isEmpty();
    }

    private UserAccount staff(Long id, String employeeId, UserRole role) {
        return UserAccount.builder()
                .id(id).employeeId(employeeId).passwordHash("x").passwordResetRequired(false)
                .role(role).fullName("Test Engineer")
                .subdivisionId(100L).distributionCenterId(10L)
                .enabled(true).notificationsPushEnabled(false).build();
    }

    // ---------- searchDirectory ----------

    @Test
    @DisplayName("searchDirectory: engineer caller is pinned to their own DC")
    void searchDirectory_engineer_pinnedToOwnDc() {
        AuthenticatedStaff eng = new AuthenticatedStaff(1L, "E", UserRole.ENGINEER, 100L, 10L, false);
        Pageable pg = PageRequest.of(0, 20);
        when(users.search(eq(100L), eq(UserRole.TECHNICIAN), eq(10L), eq(true), eq(pg)))
                .thenReturn(new PageImpl<>(List.of(staff(2L, "T-1", UserRole.TECHNICIAN))));

        PageResponse<StaffDirectoryEntryResponse> page = service.searchDirectory(
                eng, UserRole.TECHNICIAN, null, true, pg);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).role()).isEqualTo(UserRole.TECHNICIAN);

        ArgumentCaptor<Long> dcCaptor = ArgumentCaptor.forClass(Long.class);
        verify(users).search(eq(100L), eq(UserRole.TECHNICIAN), dcCaptor.capture(), eq(true), eq(pg));
        assertThat(dcCaptor.getValue()).isEqualTo(10L); // engineer's DC overrode the null request param
    }

    @Test
    @DisplayName("searchDirectory: engineer requesting a different DC → 403 FORBIDDEN")
    void searchDirectory_engineer_otherDc_forbidden() {
        AuthenticatedStaff eng = new AuthenticatedStaff(1L, "E", UserRole.ENGINEER, 100L, 10L, false);

        assertThatThrownBy(() ->
                service.searchDirectory(eng, UserRole.TECHNICIAN, 999L, true, PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("searchDirectory: admin can pass an explicit DC within their subdivision")
    void searchDirectory_admin_explicitDc_passesThrough() {
        AuthenticatedStaff admin = new AuthenticatedStaff(5L, "A", UserRole.ADMIN, 100L, null, false);
        Pageable pg = PageRequest.of(0, 20);
        when(users.search(eq(100L), eq(UserRole.TECHNICIAN), eq(20L), any(), eq(pg)))
                .thenReturn(new PageImpl<>(List.of()));

        service.searchDirectory(admin, UserRole.TECHNICIAN, 20L, null, pg);

        verify(users).search(eq(100L), eq(UserRole.TECHNICIAN), eq(20L), eq(null), eq(pg));
    }
}

