package com.example.complaints.notification.mapper;

import com.example.complaints.common.util.DateUtils;
import com.example.complaints.notification.dto.DeviceTokenResponse;
import com.example.complaints.notification.model.DeviceToken;
import org.springframework.stereotype.Component;

/** Hand-written per hard-rule #3 (no MapStruct). */
@Component
public class DeviceTokenMapper {

    public DeviceTokenResponse toResponse(DeviceToken d) {
        return new DeviceTokenResponse(
                d.getId(),
                d.getDeviceId(),
                d.getPlatform(),
                d.getAppVersion(),
                d.isActive(),
                DateUtils.toIst(d.getCreatedAt()),
                DateUtils.toIst(d.getUpdatedAt())
        );
    }
}

