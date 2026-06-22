package com.example.complaints.storage;

/**
 * Result of a successful {@link StorageService#store} call.
 *
 * @param key         opaque storage key (e.g. {@code complaint/123/COMPLAINT/abc.jpg}); used later
 *                    to fetch / delete / sign URLs
 * @param contentType MIME type the object was stored with
 * @param sizeBytes   final on-disk / on-bucket size in bytes
 */
public record StoredObject(String key, String contentType, long sizeBytes) {
}

