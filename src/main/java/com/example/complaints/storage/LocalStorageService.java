package com.example.complaints.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Filesystem-backed {@link StorageService} for the {@code dev} profile (and any environment that
 * sets {@code app.storage.type=local}). Writes objects under
 * {@code ${app.storage.local-path}/<key>}; subdirectories are created as needed.
 *
 * <p>Returned {@code signedReadUrl} is a non-signed {@code file://} URL — fine for the dev loop
 * (the dev frontend reads images via the backend's {@code GET /api/v1/consumer/complaints/...}
 * proxy), but <b>not</b> safe for any deployed environment. Production read URLs come from
 * {@code GcsStorageService} once it lands.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "local", matchIfMissing = true)
@Slf4j
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(StorageProperties props) {
        if (props.localPath() == null || props.localPath().isBlank()) {
            throw new StorageException("app.storage.local-path is required when app.storage.type=local");
        }
        this.root = Path.of(props.localPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new StorageException("Failed to create local storage root: " + root, e);
        }
        log.info("LocalStorageService initialised at {}", root);
    }

    @Override
    public StoredObject store(String key, InputStream content, String contentType, long sizeBytes) {
        Path target = resolveSafe(key);
        try {
            Files.createDirectories(target.getParent());
            long written;
            try (InputStream in = content) {
                written = Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredObject(key, contentType, written);
        } catch (IOException e) {
            throw new StorageException("Failed to write object: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolveSafe(key));
        } catch (IOException e) {
            throw new StorageException("Failed to delete object: " + key, e);
        }
    }

    @Override
    public String signedReadUrl(String key, Duration ttl) {
        // Dev only: file:// URLs are not actually signed and the ttl is ignored.
        // Documented on the interface — kept here for symmetry with the GCS impl.
        return resolveSafe(key).toUri().toString();
    }

    /**
     * Resolve {@code key} under {@link #root} and reject any path that escapes the root
     * (defence-in-depth against {@code ../} in keys, even though callers always generate keys
     * server-side from UUIDs).
     */
    private Path resolveSafe(String key) {
        Path candidate = root.resolve(key).normalize();
        if (!candidate.startsWith(root)) {
            throw new StorageException("Storage key escapes root: " + key);
        }
        return candidate;
    }
}

