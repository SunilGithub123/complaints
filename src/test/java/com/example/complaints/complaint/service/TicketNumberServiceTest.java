package com.example.complaints.complaint.service;

import com.example.complaints.complaint.ComplaintProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage of {@link TicketNumberService} — wiring + format only.
 *
 * <p>The advisory-lock contention path is covered separately by {@code TicketNumberServiceIT}
 * against a real Postgres (Mockito cannot exercise {@code pg_advisory_xact_lock}).</p>
 */
class TicketNumberServiceTest {

    private EntityManager em;
    private Query lockQuery;
    private Query upsertQuery;
    private TicketNumberService service;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        lockQuery = mock(Query.class);
        upsertQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return sql.contains("pg_advisory_xact_lock") ? lockQuery : upsertQuery;
        });
        when(lockQuery.setParameter(anyString(), anyString())).thenReturn(lockQuery);
        when(upsertQuery.setParameter(anyString(), anyString())).thenReturn(upsertQuery);
        when(lockQuery.getSingleResult()).thenReturn(1);

        service = new TicketNumberService(em,
                new ComplaintProperties(24, 3, 1_048_576L, "MH"));
    }

    @Test
    @DisplayName("nextTicketNumber formats prefix + IST yyyyMM + zero-padded 8-digit sequence")
    void nextTicketNumber_formatsCorrectly() {
        when(upsertQuery.getSingleResult()).thenReturn(42L);

        String ticket = service.nextTicketNumber();

        assertThat(ticket)
                .startsWith("MH")
                .hasSize(2 + 6 + 8)
                .endsWith("00000042");
        verify(em, times(2)).createNativeQuery(anyString());
        verify(lockQuery).getSingleResult();   // advisory lock acquired
        verify(upsertQuery).getSingleResult(); // upsert ran
    }

    @Test
    @DisplayName("nextTicketNumber zero-pads small sequence numbers to 8 digits")
    void nextTicketNumber_zeroPadsSmallNumbers() {
        when(upsertQuery.getSingleResult()).thenReturn(1L);

        String ticket = service.nextTicketNumber();

        assertThat(ticket).endsWith("00000001");
    }
}

