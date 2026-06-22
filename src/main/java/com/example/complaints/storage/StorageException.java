package com.example.complaints.storage;

/**
 * Storage-layer failure (filesystem I/O, GCS upload, etc.) bubbled up by {@link StorageService}
 * implementations. Mapped to {@code STORAGE_UNAVAILABLE} in {@code GlobalExceptionHandler}.
 *
 * <p>Distinct from {@code BusinessException} because storage failures are infrastructure errors,
 * not business-rule violations — they should be retried / alerted, not surfaced as 4xx.</p>
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}

