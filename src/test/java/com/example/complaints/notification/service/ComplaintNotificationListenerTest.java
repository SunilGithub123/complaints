package com.example.complaints.notification.service;

import com.example.complaints.auth.service.StaffLookupService;
import com.example.complaints.auth.service.StaffScopeView;
import com.example.complaints.complaint.event.ComplaintAssignedEvent;
import com.example.complaints.complaint.event.ComplaintCancelledEvent;
import com.example.complaints.complaint.event.ComplaintClosedEvent;
import com.example.complaints.complaint.event.ComplaintReassignedEvent;
import com.example.complaints.complaint.event.ComplaintRejectedEvent;
import com.example.complaints.complaint.event.ComplaintResolvedEvent;
import com.example.complaints.complaint.event.ComplaintSubmittedEvent;
import com.example.complaints.complaint.event.FeedbackSubmittedEvent;
import com.example.complaints.complaint.event.SlaBreachedEvent;
import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.complaint.model.ComplaintStatus;
import com.example.complaints.masterdata.service.DistributionCenterService;
import com.example.complaints.notification.dto.PushPayload;
import com.example.complaints.notification.model.DevicePlatform;
import com.example.complaints.notification.model.DeviceToken;
import com.example.complaints.notification.model.PushType;
import com.example.complaints.notification.repository.DeviceTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.complaints.auth.model.UserRole;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplaintNotificationListenerTest {

    private DeviceTokenRepository devices;
    private PushService push;
    private StaffLookupService staff;
    private DistributionCenterService dcs;
    private ComplaintNotificationListener listener;

    @BeforeEach
    void setUp() {
        devices = mock(DeviceTokenRepository.class);
        push = mock(PushService.class);
        staff = mock(StaffLookupService.class);
        dcs = mock(DistributionCenterService.class);
        PushPayloadFactory payloads = new PushPayloadFactory();
        listener = new ComplaintNotificationListener(devices, push, payloads, staff, dcs);
    }

    private DeviceToken staffDevice(Long userId) {
        return DeviceToken.builder().id(1L).userId(userId)
                .deviceId("dev-" + userId).platform(DevicePlatform.ANDROID)
                .pushToken("tok").active(true).build();
    }

    private DeviceToken consumerDevice(Long consumerMasterId) {
        return DeviceToken.builder().id(2L).consumerMasterId(consumerMasterId)
                .deviceId("dev-c-" + consumerMasterId).platform(DevicePlatform.IOS)
                .pushToken("tok").active(true).build();
    }

    private StaffScopeView staffView(Long userId) {
        return new StaffScopeView(userId, UserRole.ENGINEER, 1L, 10L, true);
    }

    @Test
    @DisplayName("onAssigned: LOW severity → tech only, engineer NOT cc'd (avoid noise)")
    void onAssigned_lowSeverity_noEngineerCc() {
        when(devices.findByUserIdAndActiveTrue(42L)).thenReturn(List.of(staffDevice(42L)));

        listener.onAssigned(new ComplaintAssignedEvent(
                7L, "MH001", 42L, 11L, 10L, ComplaintSeverity.LOW, 99L));

        verify(devices).findByUserIdAndActiveTrue(42L);
        verify(devices, never()).findByUserIdAndActiveTrue(11L); // engineer never fetched
        verify(push, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("onAssigned: HIGH severity → tech AND engineer notified, payload type=COMPLAINT_ASSIGNED")
    void onAssigned_highSeverity_engineerCcd() {
        when(devices.findByUserIdAndActiveTrue(42L)).thenReturn(List.of(staffDevice(42L)));
        when(devices.findByUserIdAndActiveTrue(11L)).thenReturn(List.of(staffDevice(11L)));

        listener.onAssigned(new ComplaintAssignedEvent(
                7L, "MH001", 42L, 11L, 10L, ComplaintSeverity.HIGH, 99L));

        ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        verify(push, times(2)).send(any(), payload.capture());
        assertThat(payload.getValue().type()).isEqualTo(PushType.COMPLAINT_ASSIGNED);
        assertThat(payload.getValue().ticketNo()).isEqualTo("MH001");
        assertThat(payload.getValue().body()).contains("HIGH");
    }

    @Test
    @DisplayName("onFeedback: rating > 2 → tech + engineer; admin NOT escalated")
    void onFeedback_highRating_noAdminEscalation() {
        when(devices.findByUserIdAndActiveTrue(anyLong())).thenReturn(List.of());

        listener.onFeedback(new FeedbackSubmittedEvent(7L, "MH001", 42L, 11L, 10L, 5, false));

        verify(devices).findByUserIdAndActiveTrue(42L);
        verify(devices).findByUserIdAndActiveTrue(11L);
        verify(dcs, never()).getSubdivisionId(anyLong());
        verify(staff, never()).findActiveAdminForSubdivision(anyLong());
    }

    @Test
    @DisplayName("onFeedback: rating ≤ 2 → tech + engineer + admin (two-hop DC → subdivision → admin)")
    void onFeedback_lowRating_adminEscalated() {
        when(devices.findByUserIdAndActiveTrue(anyLong())).thenReturn(List.of());
        when(dcs.getSubdivisionId(10L)).thenReturn(1L);
        when(staff.findActiveAdminForSubdivision(1L))
                .thenReturn(Optional.of(new StaffScopeView(7L, UserRole.ADMIN, 1L, null, true)));

        listener.onFeedback(new FeedbackSubmittedEvent(7L, "MH001", 42L, 11L, 10L, 1, false));

        verify(staff).findActiveAdminForSubdivision(1L);
        verify(devices).findByUserIdAndActiveTrue(7L); // admin's devices fetched
    }

    @Test
    @DisplayName("onSubmitted: looks up active engineer for DC, fans out to engineer's devices")
    void onSubmitted_findsEngineerForDc() {
        when(staff.findActiveEngineerForDc(10L)).thenReturn(Optional.of(staffView(11L)));
        when(devices.findByUserIdAndActiveTrue(11L)).thenReturn(List.of(staffDevice(11L)));

        listener.onSubmitted(new ComplaintSubmittedEvent(
                7L, "MH001", 99L, "+919999999999", 3L, 10L));

        ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        verify(push).send(any(), payload.capture());
        assertThat(payload.getValue().type()).isEqualTo(PushType.COMPLAINT_SUBMITTED);
    }

    @Test
    @DisplayName("onClosed: only consumer notified, no staff fan-out (consumer-bound event)")
    void onClosed_consumerOnly() {
        when(devices.findByConsumerMasterIdAndActiveTrue(99L)).thenReturn(List.of(consumerDevice(99L)));

        listener.onClosed(new ComplaintClosedEvent(
                7L, "MH001", 99L, 42L, 11L, false, Instant.now(), 11L));

        verify(devices).findByConsumerMasterIdAndActiveTrue(99L);
        verify(devices, never()).findByUserIdAndActiveTrue(anyLong());
        verify(push, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("per-recipient isolation: one bad device does not block the next (caught + logged)")
    void perRecipientIsolation_failureDoesNotBlockOthers() {
        DeviceToken bad = staffDevice(42L);
        DeviceToken good = staffDevice(42L);
        good.setDeviceId("dev-good");
        when(devices.findByUserIdAndActiveTrue(42L)).thenReturn(List.of(bad, good));
        doThrow(new RuntimeException("simulated FCM 500")).when(push).send(eq(bad), any());

        // Must NOT throw — exception isolated to the first send call.
        listener.onAssigned(new ComplaintAssignedEvent(
                7L, "MH001", 42L, 11L, 10L, ComplaintSeverity.LOW, 99L));

        verify(push, times(2)).send(any(), any()); // both attempted
    }

    @Test
    @DisplayName("onReassigned: notifies new tech + previous tech + engineer (3 fan-out targets)")
    void onReassigned_threeRecipients() {
        when(devices.findByUserIdAndActiveTrue(anyLong())).thenReturn(List.of());

        listener.onReassigned(new ComplaintReassignedEvent(
                7L, "MH001", 41L, 42L, 9L, 10L, 11L, 99L, "more skilled"));

        verify(devices).findByUserIdAndActiveTrue(42L); // new tech
        verify(devices).findByUserIdAndActiveTrue(41L); // previous tech
        verify(devices).findByUserIdAndActiveTrue(11L); // engineer
    }

    @Test
    @DisplayName("onSlaBreached: notifies assigned tech + assigned engineer (once per breach, not per tick)")
    void onSlaBreached_techAndEngineer() {
        when(devices.findByUserIdAndActiveTrue(anyLong())).thenReturn(List.of());

        listener.onSlaBreached(new SlaBreachedEvent(
                7L, "MH001", 42L, 11L, 10L, ComplaintStatus.IN_PROGRESS,
                Instant.now().minusSeconds(60), Instant.now()));

        verify(devices).findByUserIdAndActiveTrue(42L);
        verify(devices).findByUserIdAndActiveTrue(11L);
    }

    @Test
    @DisplayName("onRejected: only consumer; reason inlined into body")
    void onRejected_consumerWithReason() {
        when(devices.findByConsumerMasterIdAndActiveTrue(99L)).thenReturn(List.of(consumerDevice(99L)));

        listener.onRejected(new ComplaintRejectedEvent(
                7L, "MH001", 99L, 10L, "Already resolved offline", 11L));

        ArgumentCaptor<PushPayload> payload = ArgumentCaptor.forClass(PushPayload.class);
        verify(push).send(any(), payload.capture());
        assertThat(payload.getValue().body()).contains("Already resolved offline");
    }

    @Test
    @DisplayName("onCancelled: notifies the engineer-of-DC (no assigned tech on SUBMITTED-only cancellation)")
    void onCancelled_engineerOfDc() {
        when(staff.findActiveEngineerForDc(10L)).thenReturn(Optional.of(staffView(11L)));
        when(devices.findByUserIdAndActiveTrue(11L)).thenReturn(List.of());

        listener.onCancelled(new ComplaintCancelledEvent(
                7L, "MH001", 99L, "MH00010001", 10L, "consumer changed mind"));

        verify(staff).findActiveEngineerForDc(10L);
        verify(devices).findByUserIdAndActiveTrue(11L);
    }

    @Test
    @DisplayName("onResolved: notifies both consumer and engineer (no SMS fallback yet)")
    void onResolved_consumerAndEngineer() {
        when(devices.findByConsumerMasterIdAndActiveTrue(99L)).thenReturn(List.of());
        when(devices.findByUserIdAndActiveTrue(11L)).thenReturn(List.of());

        listener.onResolved(new ComplaintResolvedEvent(
                7L, "MH001", 99L, 42L, 11L, false, Instant.now(), 42L));

        verify(devices).findByConsumerMasterIdAndActiveTrue(99L);
        verify(devices).findByUserIdAndActiveTrue(11L);
    }
}

