package com.example.complaints.notification.service;

import com.example.complaints.notification.DeviceTokenSweepProperties;
import com.example.complaints.notification.repository.DeviceTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceTokenSweepJobTest {

    @Test
    @DisplayName("sweep — calls repo with cutoff = now − inactivityDays")
    void sweep_callsRepoWithExpectedCutoff() {
        DeviceTokenRepository repo = mock(DeviceTokenRepository.class);
        when(repo.markInactiveOlderThan(any(Instant.class))).thenReturn(3);
        DeviceTokenSweepProperties props = new DeviceTokenSweepProperties(60, true);
        DeviceTokenSweepJob job = new DeviceTokenSweepJob(repo, props);

        Instant before = Instant.now();
        job.sweep();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).markInactiveOlderThan(cutoff.capture());
        Instant captured = cutoff.getValue();
        // Cutoff must sit within [before, after] shifted back by 60 days.
        assertThat(captured).isBetween(
                before.minus(Duration.ofDays(60)),
                after.minus(Duration.ofDays(60))
        );
    }

    @Test
    @DisplayName("sweep — kill-switch disables the run entirely")
    void sweep_skippedWhenDisabled() {
        DeviceTokenRepository repo = mock(DeviceTokenRepository.class);
        DeviceTokenSweepProperties props = new DeviceTokenSweepProperties(60, false);
        DeviceTokenSweepJob job = new DeviceTokenSweepJob(repo, props);

        job.sweep();

        verify(repo, never()).markInactiveOlderThan(any(Instant.class));
    }
}


