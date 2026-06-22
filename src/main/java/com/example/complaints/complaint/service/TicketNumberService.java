package com.example.complaints.complaint.service;

import com.example.complaints.common.util.DateUtils;
import com.example.complaints.complaint.ComplaintProperties;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Issues the next unique complaint ticket number for the current IST month
 * (TECHNICAL_DESIGN.md 4). Format: {@code <ticketPrefix><YYYYMM><8-digit-seq>}
 * — e.g. {@code MH202606 00000123}.
 *
 * <p>Concurrency contract:
 * <ul>
 *   <li>Always runs in its <b>own</b> transaction ({@link Propagation#REQUIRES_NEW}) so the row-level
 *       lock from the upsert is released as soon as the number is minted — long-running submit
 *       transactions never serialise the whole month behind their commit.</li>
 *   <li>Holds a Postgres <b>advisory transaction lock</b> on
 *       {@code hashtext('complaint_seq_' || yearMonth)} for the duration of the call — defence-in-depth
 *       against any future caller that bypasses the upsert path.</li>
 *   <li>The upsert itself is atomic ({@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}), so even
 *       without the advisory lock Postgres would never hand out duplicate numbers within the same
 *       month.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketNumberService {

    private static final String LOCK_KEY_PREFIX = "complaint_seq_";
    private static final String SEQ_DIGITS = "%08d";

    private final EntityManager em;
    private final ComplaintProperties props;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextTicketNumber() {
        String yearMonth = DateUtils.currentYearMonthIst();
        acquireAdvisoryLock(yearMonth);
        long assigned = upsertAndReturnAssignedValue(yearMonth);
        String ticket = String.format(Locale.ROOT, "%s%s" + SEQ_DIGITS,
                props.ticketPrefix(), yearMonth, assigned);
        log.debug("Minted ticket {} (month={}, seq={})", ticket, yearMonth, assigned);
        return ticket;
    }

    private void acquireAdvisoryLock(String yearMonth) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:key))")
                .setParameter("key", LOCK_KEY_PREFIX + yearMonth)
                .getSingleResult();
    }

    /**
     * Atomic "claim the next sequence number for {@code yearMonth}".
     *
     * <p>{@code next_value} stores the value to assign on the <i>next</i> call. So:
     * <ul>
     *   <li>First call for the month → inserts {@code (yearMonth, 2)}, returns {@code 1}.</li>
     *   <li>Subsequent call → bumps {@code next_value} by 1, returns the value it had before the bump.</li>
     * </ul>
     */
    private long upsertAndReturnAssignedValue(String yearMonth) {
        Object result = em.createNativeQuery(
                """
                INSERT INTO complaint_sequence(year_month, next_value)
                VALUES (:ym, 2)
                ON CONFLICT (year_month) DO UPDATE
                  SET next_value = complaint_sequence.next_value + 1
                RETURNING next_value - 1
                """)
                .setParameter("ym", yearMonth)
                .getSingleResult();
        return ((Number) result).longValue();
    }
}

