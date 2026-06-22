package com.example.complaints.complaint.model;

import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplaintStatusTransitionTest {

    @Test
    @DisplayName("happy path: SUBMITTED → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED")
    void happyPath_allEdgesAllowed() {
        assertThat(ComplaintStatusTransition.isAllowed(ComplaintStatus.SUBMITTED,   ComplaintStatus.ASSIGNED)).isTrue();
        assertThat(ComplaintStatusTransition.isAllowed(ComplaintStatus.ASSIGNED,    ComplaintStatus.IN_PROGRESS)).isTrue();
        assertThat(ComplaintStatusTransition.isAllowed(ComplaintStatus.IN_PROGRESS, ComplaintStatus.RESOLVED)).isTrue();
        assertThat(ComplaintStatusTransition.isAllowed(ComplaintStatus.RESOLVED,    ComplaintStatus.CLOSED)).isTrue();
    }

    @Test
    @DisplayName("SUBMITTED can go straight to terminal alternatives (cancel / reject / duplicate)")
    void submitted_terminalAlternativesAllowed() {
        assertThat(ComplaintStatusTransition.isAllowed(ComplaintStatus.SUBMITTED, ComplaintStatus.CANCELLED)).isTrue();
        assertThat(ComplaintStatusTransition.isAllowed(ComplaintStatus.SUBMITTED, ComplaintStatus.REJECTED)).isTrue();
        assertThat(ComplaintStatusTransition.isAllowed(ComplaintStatus.SUBMITTED, ComplaintStatus.DUPLICATE)).isTrue();
    }

    @Test
    @DisplayName("null from-status: only initial-insert into SUBMITTED is valid")
    void nullFrom_onlySubmittedAllowed() {
        assertThat(ComplaintStatusTransition.isAllowed(null, ComplaintStatus.SUBMITTED)).isTrue();
        assertThat(ComplaintStatusTransition.isAllowed(null, ComplaintStatus.ASSIGNED)).isFalse();
        assertThat(ComplaintStatusTransition.isAllowed(null, ComplaintStatus.CLOSED)).isFalse();
    }

    @Test
    @DisplayName("requireValid throws COMPLAINT_INVALID_STATE_TRANSITION on disallowed edges")
    void requireValid_rejectsDisallowedEdge() {
        // RESOLVED → IN_PROGRESS would be a rollback; not allowed in v1.
        assertThatThrownBy(() ->
                ComplaintStatusTransition.requireValid(ComplaintStatus.RESOLVED, ComplaintStatus.IN_PROGRESS))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.COMPLAINT_INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("terminal states refuse every outgoing transition")
    void terminalStates_refuseAllOutgoing() {
        for (ComplaintStatus terminal : new ComplaintStatus[]{
                ComplaintStatus.CLOSED, ComplaintStatus.CANCELLED,
                ComplaintStatus.REJECTED, ComplaintStatus.DUPLICATE}) {
            assertThat(ComplaintStatusTransition.isTerminal(terminal)).isTrue();
            for (ComplaintStatus target : ComplaintStatus.values()) {
                assertThat(ComplaintStatusTransition.isAllowed(terminal, target))
                        .as("%s → %s should be disallowed", terminal, target)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("self-transitions are not on the allow-table (state-change validator only)")
    void selfTransition_disallowed() {
        // Reassignment (ASSIGNED → ASSIGNED with a different technician) does not go through
        // this validator at all — it mutates assigned_technician_id and keeps status. So the
        // validator correctly rejects a same-status edge.
        assertThat(ComplaintStatusTransition.isAllowed(ComplaintStatus.ASSIGNED, ComplaintStatus.ASSIGNED)).isFalse();
        assertThat(ComplaintStatusTransition.isAllowed(ComplaintStatus.IN_PROGRESS, ComplaintStatus.IN_PROGRESS)).isFalse();
    }
}

