package com.example.complaints.complaint.model;

/**
 * Source of a {@link ComplaintImage}. See {@code chk_image_type} in {@code V1.0__init_schema.sql}.
 * {@code COMPLAINT} images are uploaded by the consumer at submission. {@code RESOLUTION} images
 * are uploaded by the technician at resolution (Phase 4).
 */
public enum ComplaintImageType {
    COMPLAINT, RESOLUTION
}

