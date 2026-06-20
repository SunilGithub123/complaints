package com.example.complaints.common.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Centralized date/time helpers anchored on Asia/Kolkata (IST). See TECHNICAL_DESIGN.md §16.1.
 *
 * <p>Always use these helpers for business-time concerns (SLA deadlines, ticket-month, etc.).
 * Persistence uses {@code TIMESTAMPTZ} (UTC on disk) but business logic must operate in IST.</p>
 */
public final class DateUtils {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private DateUtils() {}

    /** Current instant. */
    public static OffsetDateTime nowIst() {
        return OffsetDateTime.now(IST);
    }

    /** Today's calendar date in IST. */
    public static LocalDate todayIst() {
        return LocalDate.now(IST);
    }

    /** {@code "202606"} for the current IST month — used in the ticket-number and the {@code complaint_sequence} key. */
    public static String currentYearMonthIst() {
        return YEAR_MONTH.format(LocalDate.now(IST));
    }

    /** Convert an instant from the database to an IST {@link OffsetDateTime} for serialization. */
    public static OffsetDateTime toIst(Instant instant) {
        return instant == null ? null : instant.atZone(IST).toOffsetDateTime();
    }
}

