package com.example.complaints.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Storage configuration bound from {@code app.storage.*}. See {@code application-{profile}.yml}.
 *
 * <p>{@code type=local} writes to {@code localPath} on the local filesystem (dev). {@code type=gcs}
 * uploads to {@code gcsBucket} via the GCS client (test/prod) — wiring lands in a follow-up stage
 * once the {@code google-cloud-storage} dependency is added. {@code signedUrlTtlSeconds} is the TTL
 * applied to read URLs returned to consumers; the storage strategy is free to ignore it (local
 * impl returns a non-signed file URL since dev has no public access).</p>
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        Type type,
        String localPath,
        String gcsBucket,
        int signedUrlTtlSeconds
) {
    public StorageProperties {
        if (type == null) {
            type = Type.LOCAL;
        }
        if (signedUrlTtlSeconds <= 0) {
            signedUrlTtlSeconds = 900; // 15 minutes — matches FRONTEND_DESIGN.md image-view contract
        }
    }

    public enum Type { LOCAL, GCS }
}

