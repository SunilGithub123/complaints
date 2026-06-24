package com.example.complaints.notification.service;

import com.example.complaints.common.exception.BusinessException;
import com.example.complaints.common.exception.ErrorCode;
import com.example.complaints.notification.dto.DeviceRegistrationRequest;
import com.example.complaints.notification.mapper.DeviceTokenMapper;
import com.example.complaints.notification.model.DevicePlatform;
import com.example.complaints.notification.model.DeviceToken;
import com.example.complaints.notification.repository.DeviceTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceTokenServiceTest {

    private DeviceTokenRepository repo;
    private DeviceTokenService service;

    @BeforeEach
    void setUp() {
        repo = mock(DeviceTokenRepository.class);
        DeviceTokenMapper mapper = new DeviceTokenMapper();
        service = new DeviceTokenService(repo, mapper);
        when(repo.save(any(DeviceToken.class))).thenAnswer(inv -> {
            DeviceToken d = inv.getArgument(0);
            d.setId(99L);
            return d;
        });
    }

    @Test
    @DisplayName("registerForConsumer: first time → created=true, row persisted with consumer FK, active=true")
    void registerForConsumer_firstTime_created() {
        when(repo.findFirstByConsumerMasterIdAndDeviceIdAndActiveTrue(42L, "dev-uuid-1"))
                .thenReturn(Optional.empty());

        DeviceTokenService.RegistrationResult result = service.registerForConsumer(42L,
                new DeviceRegistrationRequest("dev-uuid-1", "ANDROID", "fcm_token_abc", "1.4.0"));

        assertThat(result.created()).isTrue();
        assertThat(result.response().active()).isTrue();
        assertThat(result.response().platform()).isEqualTo(DevicePlatform.ANDROID);

        ArgumentCaptor<DeviceToken> saved = ArgumentCaptor.forClass(DeviceToken.class);
        verify(repo).save(saved.capture());
        DeviceToken row = saved.getValue();
        assertThat(row.getConsumerMasterId()).isEqualTo(42L);
        assertThat(row.getUserId()).isNull();
        assertThat(row.getPushToken()).isEqualTo("fcm_token_abc");
        assertThat(row.isActive()).isTrue();
    }

    @Test
    @DisplayName("registerForConsumer: same deviceId again → refresh in place, created=false, push_token overwritten")
    void registerForConsumer_refresh_inPlace() {
        DeviceToken existing = DeviceToken.builder()
                .id(7L).consumerMasterId(42L).deviceId("dev-uuid-1")
                .platform(DevicePlatform.ANDROID).pushToken("old_token")
                .appVersion("1.3.0").active(true).build();
        when(repo.findFirstByConsumerMasterIdAndDeviceIdAndActiveTrue(42L, "dev-uuid-1"))
                .thenReturn(Optional.of(existing));

        DeviceTokenService.RegistrationResult result = service.registerForConsumer(42L,
                new DeviceRegistrationRequest("dev-uuid-1", "ANDROID", "new_token", "1.4.0"));

        assertThat(result.created()).isFalse();
        assertThat(existing.getPushToken()).isEqualTo("new_token"); // Hibernate dirty-check
        assertThat(existing.getAppVersion()).isEqualTo("1.4.0");
        verify(repo, never()).save(any()); // refresh path does not call save()
    }

    @Test
    @DisplayName("registerForUser: unknown platform → DEVICE_PLATFORM_UNSUPPORTED, never queries or saves")
    void registerForUser_badPlatform_rejected() {
        assertThatThrownBy(() -> service.registerForUser(7L,
                new DeviceRegistrationRequest("dev-x", "WINDOWS_PHONE", "tok", null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DEVICE_PLATFORM_UNSUPPORTED);
        verify(repo, never()).findFirstByUserIdAndDeviceIdAndActiveTrue(any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("revokeForConsumer: missing row → 204 no-op (idempotent, no save)")
    void revokeForConsumer_missing_isIdempotent() {
        when(repo.findFirstByConsumerMasterIdAndDeviceId(42L, "dev-uuid-1"))
                .thenReturn(Optional.empty());

        service.revokeForConsumer(42L, "dev-uuid-1");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("revokeForUser: active row → flips active=false (Hibernate dirty-check, no save call)")
    void revokeForUser_active_flipsActive() {
        DeviceToken existing = DeviceToken.builder()
                .id(7L).userId(11L).deviceId("dev-uuid-1")
                .platform(DevicePlatform.IOS).pushToken("apn_token")
                .active(true).build();
        when(repo.findFirstByUserIdAndDeviceId(11L, "dev-uuid-1"))
                .thenReturn(Optional.of(existing));

        service.revokeForUser(11L, "dev-uuid-1");

        assertThat(existing.isActive()).isFalse();
        verify(repo, never()).save(any());
    }
}

