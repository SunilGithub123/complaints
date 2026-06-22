package com.example.complaints.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * Pluggable binary storage for complaint images (and any future blob attachments). v1 has two
 * intended implementations selected by {@code app.storage.type}:
 * <ul>
 *   <li>{@code local} — {@link LocalStorageService}, dev profile. Writes to a directory on disk.</li>
 *   <li>{@code gcs} — {@code GcsStorageService}, test/prod. Uploads to a Google Cloud Storage
 *       bucket. <b>Wiring lands in a follow-up stage</b> once {@code google-cloud-storage} is on
 *       the classpath.</li>
 * </ul>
 *
 * <p>The interface is intentionally small (store / delete / signedReadUrl) — Stage 10b only
 * needs upload + read-URL, and delete is reserved for the future complaint-cancel cleanup
 * (Phase 5). All implementations must be safe to call concurrently.</p>
 */
public interface StorageService {

    /**
     * Persist a binary object under {@code key}. Implementations must overwrite any existing
     * object at the same key (callers always generate fresh keys, so overwrites are
     * effectively never expected — overwrite-on-collision is a defensive default).
     *
     * @param key         opaque storage key, e.g. {@code complaint/123/COMPLAINT/abc.jpg}
     * @param content     input stream to read; implementations must close the stream
     * @param contentType MIME type to record alongside the object
     * @param sizeBytes   number of bytes the caller expects to write (used by GCS for
     *                    pre-allocated uploads; {@link LocalStorageService} ignores it)
     * @return descriptor of the stored object (key, content type, final size)
     * @throws StorageException if the underlying store fails
     */
    StoredObject store(String key, InputStream content, String contentType, long sizeBytes);

    /**
     * Delete the object at {@code key}. No-op if the key does not exist (idempotent).
     */
    void delete(String key);

    /**
     * Return a URL the consumer's browser can fetch directly for {@code ttl}. For {@code local},
     * this is a non-signed file URL — dev only; do not deploy {@code local} to a shared env.
     */
    String signedReadUrl(String key, Duration ttl);
}

