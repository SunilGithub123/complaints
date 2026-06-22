package com.example.complaints.complaint;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Complaint module configuration bound from {@code app.complaint.*}. Defaults mirror the values
 * in {@code application.yml} so missing entries fail-soft to sensible production values.
 *
 * @param defaultSlaHours fallback SLA window if a category has no override (Phase 4 uses this)
 * @param maxImages       maximum images per complaint (Stage 10b enforcement)
 * @param maxImageBytes   per-image hard cap in bytes — 1 MB by default per FRONTEND_DESIGN
 * @param ticketPrefix    constant prefix on every ticket number (e.g. {@code "MH"})
 */
@ConfigurationProperties(prefix = "app.complaint")
public record ComplaintProperties(
        int defaultSlaHours,
        int maxImages,
        long maxImageBytes,
        String ticketPrefix
) {
    public ComplaintProperties {
        if (defaultSlaHours <= 0) defaultSlaHours = 24;
        if (maxImages <= 0) maxImages = 3;
        if (maxImageBytes <= 0) maxImageBytes = 1_048_576L;
        if (ticketPrefix == null || ticketPrefix.isBlank()) ticketPrefix = "MH";
    }
}

