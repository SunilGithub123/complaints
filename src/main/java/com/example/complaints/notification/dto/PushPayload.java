package com.example.complaints.notification.dto;

import com.example.complaints.notification.model.PushType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stage 21.2 — frozen FCM data-only payload per
 * {@code docs/STAGE_21_DEVICE_TOKEN_CONTRACT.md §4} v1.0. {@code eventOccurredAt} carries
 * the server clock at {@code AFTER_COMMIT} so the FE can render correct "n minutes ago"
 * labels when a batch is delivered after the device was offline / killed (per FE
 * sign-off §4 delta).
 *
 * <p>{@link #toFcmDataFrame()} renders the v1 wire shape — all-string keys/values per
 * the FCM constraint. Bump {@code schemaVersion} only on a breaking change.</p>
 */
public record PushPayload(
        PushType type,
        String ticketNo,
        Long complaintId,
        String title,
        String body,
        OffsetDateTime eventOccurredAt,
        int schemaVersion
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Renders the FCM {@code data} message frame — all-string per FCM contract. */
    public Map<String, String> toFcmDataFrame() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type",            type.name());
        data.put("ticketNo",        ticketNo);
        data.put("complaintId",     String.valueOf(complaintId));
        data.put("title",           title);
        data.put("body",            body);
        data.put("eventOccurredAt", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(eventOccurredAt));
        data.put("schemaVersion",   String.valueOf(schemaVersion));
        return data;
    }
}

