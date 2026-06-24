package com.example.complaints.notification.service;

import com.example.complaints.notification.dto.PushPayload;
import com.example.complaints.notification.model.DeviceToken;

/**
 * Stage 21.2 — push provider abstraction per
 * {@code docs/STAGE_21_DEVICE_TOKEN_CONTRACT.md §6}.
 *
 * <p>Two implementations land separately, profile-switched:</p>
 * <ul>
 *   <li>{@link ConsolePushService} — dev / test, logs the metadata, no external side effect.</li>
 *   <li>{@code FcmPushService} (Stage 21.3, deferred until GCP / FCM service-account
 *       credentials are provisioned) — wraps {@code firebase-admin}.</li>
 * </ul>
 *
 * <p>Per §6.1 the caller (the listener) owns per-recipient failure isolation — a
 * {@link RuntimeException} from one {@code send} must never block the next recipient.</p>
 */
public interface PushService {

    /**
     * Deliver a payload to a single device. Implementations must not log the
     * {@code pushToken} value or the rendered {@code body} per §6.2.
     */
    void send(DeviceToken target, PushPayload payload);
}

