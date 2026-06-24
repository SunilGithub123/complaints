package com.example.complaints.notification.service;

import com.example.complaints.notification.dto.PushPayload;
import com.example.complaints.notification.model.DeviceToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Stage 21.2 — dev / test push provider. Emits a single INFO log line with the
 * metadata fields whitelisted by contract §6.2 — {@code pushToken} and {@code body}
 * are <b>never</b> logged.
 *
 * <p>Selected by default in {@code dev} / {@code test} profiles. The FCM impl
 * (Stage 21.3) will set {@code app.push.provider=fcm} to disable this one in prod.</p>
 */
@Service
@Profile({"dev", "test"})
@ConditionalOnProperty(prefix = "app.push", name = "provider", havingValue = "console", matchIfMissing = true)
@Slf4j
public class ConsolePushService implements PushService {

    @Override
    public void send(DeviceToken target, PushPayload payload) {
        // §6.2 never-log fields: push_token, body. Whitelist below.
        log.info(
                "[PUSH] outcome=SENT event={} ticketNo={} complaintId={} recipientUserId={} consumerMasterId={} platform={} deviceId={}",
                payload.type(),
                payload.ticketNo(),
                payload.complaintId(),
                target.getUserId(),
                target.getConsumerMasterId(),
                target.getPlatform(),
                target.getDeviceId()
        );
    }
}

