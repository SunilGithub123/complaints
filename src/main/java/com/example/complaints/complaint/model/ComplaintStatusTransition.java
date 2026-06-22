package com.example.complaints.complaint.model;

import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Encoded allow-table for the complaint status state machine. The transition rules live in
 * exactly one place (this class) so the engineer / technician / admin services can all consult
 * the same source of truth — open/closed principle applied at the cost of a single tiny class.
 *
 * <p>Reassignment, severity edits, audit notes and other non-status mutations <b>do not</b> pass
 * through this validator — they keep {@code status} unchanged and so are not "transitions" in
 * the state-machine sense. Use {@link #requireValid} only when you are changing
 * {@code complaint.status}.</p>
 *
 * <h3>Transition table (v1)</h3>
 * <pre>
 *   SUBMITTED   → ASSIGNED, CANCELLED, REJECTED, DUPLICATE
 *   ASSIGNED    → IN_PROGRESS
 *   IN_PROGRESS → RESOLVED
 *   RESOLVED    → CLOSED
 *   CLOSED, CANCELLED, REJECTED, DUPLICATE → ∅  (terminal — no transitions)
 * </pre>
 *
 * <p>v1 deliberately keeps the table conservative — no rollback edges, no re-open, no
 * cross-terminal recovery. If BRD revisions add edges, do it here in one diff rather than
 * spreading {@code if/else} ladders across services.</p>
 */
public final class ComplaintStatusTransition {

    private static final Map<ComplaintStatus, Set<ComplaintStatus>> ALLOWED = Map.of(
            ComplaintStatus.SUBMITTED,   EnumSet.of(
                    ComplaintStatus.ASSIGNED,
                    ComplaintStatus.CANCELLED,
                    ComplaintStatus.REJECTED,
                    ComplaintStatus.DUPLICATE),
            ComplaintStatus.ASSIGNED,    EnumSet.of(ComplaintStatus.IN_PROGRESS),
            ComplaintStatus.IN_PROGRESS, EnumSet.of(ComplaintStatus.RESOLVED),
            ComplaintStatus.RESOLVED,    EnumSet.of(ComplaintStatus.CLOSED),
            ComplaintStatus.CLOSED,      EnumSet.noneOf(ComplaintStatus.class),
            ComplaintStatus.CANCELLED,   EnumSet.noneOf(ComplaintStatus.class),
            ComplaintStatus.REJECTED,    EnumSet.noneOf(ComplaintStatus.class),
            ComplaintStatus.DUPLICATE,   EnumSet.noneOf(ComplaintStatus.class)
    );

    private ComplaintStatusTransition() {}

    /**
     * @return {@code true} if {@code from → to} is on the allow-table. A {@code null} {@code from}
     *     (i.e. the initial insert) is only valid against {@link ComplaintStatus#SUBMITTED} — every
     *     new complaint starts in SUBMITTED state per the consumer submit flow.
     */
    public static boolean isAllowed(ComplaintStatus from, ComplaintStatus to) {
        if (to == null) {
            return false;
        }
        if (from == null) {
            return to == ComplaintStatus.SUBMITTED;
        }
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(ComplaintStatus.class)).contains(to);
    }

    /**
     * Validator entry-point for service code. Pass the complaint's current status and the
     * status the service wants to move it into.
     *
     * @throws BusinessException with {@link ErrorCode#COMPLAINT_INVALID_STATE_TRANSITION} when
     *     the move is not on the allow-table.
     */
    public static void requireValid(ComplaintStatus from, ComplaintStatus to) {
        if (!isAllowed(from, to)) {
            throw new BusinessException(ErrorCode.COMPLAINT_INVALID_STATE_TRANSITION);
        }
    }

    /** Convenience: terminal statuses cannot transition out. Used by readers (UI badges, etc.). */
    public static boolean isTerminal(ComplaintStatus status) {
        return status != null && ALLOWED.getOrDefault(status, EnumSet.noneOf(ComplaintStatus.class)).isEmpty();
    }
}

