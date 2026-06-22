package com.example.complaints.complaint.service;

import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintHistory;
import com.example.complaints.complaint.repository.ComplaintHistoryRepository;
import com.example.complaints.complaint.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Stage 15 · SLA breach scheduler. Periodically scans for open complaints whose
 * {@code sla_deadline} has elapsed and flips {@code sla_breached = true}, writing a
 * system-actor (null) history row so the FE timeline can render "SLA breached at … by system".
 *
 * <p>Cadence: every 15 minutes, at second 0 of the minute, IST. The 15-minute granularity is
 * a deliberate compromise between snappy UI feedback and not hammering the DB on a system that
 * has no truly time-critical SLA action — the existing resolve / close paths already flip the
 * flag synchronously when they detect a past-deadline complaint, so this scheduler only
 * matters for the "still in flight, technician hasn't touched it" segment.</p>
 *
 * <h3>Concurrency</h3>
 * <p>The sweep is one big {@code @Transactional} method. If a complaint is concurrently
 * mutated by an engineer / technician at the moment of sweep, Hibernate's {@code @Version}
 * check fires and the whole sweep tick rolls back. That's fine for a v1 — we retry in 15
 * minutes; the flag's role is informational, not gating. If contention becomes visible in
 * prod metrics we'll split per-row {@code REQUIRES_NEW} transactions; not worth the extra
 * complexity up-front.</p>
 *
 * <h3>State machine</h3>
 * <p>The breach flag is orthogonal to {@link com.example.complaints.complaint.model.ComplaintStatus}
 * — flipping it does <b>not</b> count as a status transition, so we deliberately don't go
 * through {@code ComplaintStatusTransition.requireValid(...)}. The history row carries
 * {@code from_status == to_status} (same as Stage 13's severity / reassignment annotations)
 * with {@code changed_by_user_id = null} to mark a system-driven event.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaMonitorService {

    /** Every 15 min, at :00/:15/:30/:45, IST. Matches TECHNICAL_DESIGN.md §1.6 and ROADMAP.md Phase 4. */
    static final String CRON = "0 */15 * * * *";

    private final ComplaintRepository complaints;
    private final ComplaintHistoryRepository history;

    @Scheduled(cron = CRON, zone = "Asia/Kolkata")
    @Transactional
    public void markBreached() {
        Instant now = Instant.now();
        List<Complaint> overdue = complaints.findBySlaBreachedFalseAndStatusInAndSlaDeadlineBefore(
                ComplaintQueryService.OPEN_STATUSES, now);
        if (overdue.isEmpty()) {
            log.debug("SLA sweep at {}: no overdue complaints", now);
            return;
        }
        for (Complaint c : overdue) {
            c.setSlaBreached(true);
            history.save(ComplaintHistory.builder()
                    .complaintId(c.getId())
                    .fromStatus(c.getStatus())
                    .toStatus(c.getStatus())
                    .changedByUserId(null) // system
                    .note("SLA breached")
                    .build());
        }
        log.info("SLA sweep at {}: flagged {} complaints as breached", now, overdue.size());
    }
}

