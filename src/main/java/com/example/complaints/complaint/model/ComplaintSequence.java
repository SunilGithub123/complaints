package com.example.complaints.complaint.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Per-month ticket-number sequence. One row per {@code YYYYMM} (IST). Mutated only by
 * {@code TicketNumberService} under a Postgres advisory lock, so concurrent submissions in the
 * same month never collide on {@code next_value}. Sequence resets each month
 * (see TECHNICAL_DESIGN.md 4).
 */
@Entity
@Table(name = "complaint_sequence")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ComplaintSequence {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "year_month", length = 6, nullable = false, updatable = false)
    private String yearMonth;

    @Column(name = "next_value", nullable = false)
    private long nextValue;
}




