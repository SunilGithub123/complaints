package com.example.complaints.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Single source of truth for every business error in the system. See TECHNICAL_DESIGN.md §16.4.
 *
 * <p>Add a new entry here BEFORE throwing it from a service. Never throw a raw RuntimeException
 * from a service for a business condition.</p>
 *
 * <p>The default {@link #message} is a fallback; the localized message is resolved via the
 * i18n bundle keyed by the enum name (e.g. {@code error.CONSUMER_NOT_FOUND}).</p>
 */
public enum ErrorCode {

    // ---------- Generic / framework ----------
    VALIDATION_FAILED              (HttpStatus.BAD_REQUEST,           "Request validation failed"),
    UNAUTHORIZED                   (HttpStatus.UNAUTHORIZED,          "Authentication is required"),
    FORBIDDEN                      (HttpStatus.FORBIDDEN,             "Access is denied"),
    NOT_FOUND                      (HttpStatus.NOT_FOUND,             "Resource not found"),
    CONFLICT                       (HttpStatus.CONFLICT,              "Conflicting resource state"),
    INTERNAL_ERROR                 (HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected internal error"),

    // ---------- Auth / OTP / staff ----------
    CONSUMER_NOT_FOUND             (HttpStatus.NOT_FOUND,             "Consumer ID not found in master data"),
    CONSUMER_INACTIVE              (HttpStatus.FORBIDDEN,             "Consumer record is inactive"),
    OTP_INVALID                    (HttpStatus.BAD_REQUEST,           "Invalid OTP"),
    OTP_EXPIRED                    (HttpStatus.BAD_REQUEST,           "OTP has expired"),
    OTP_RATE_LIMIT                 (HttpStatus.TOO_MANY_REQUESTS,     "Too many OTP requests; try again later"),
    OTP_COOLDOWN                   (HttpStatus.TOO_MANY_REQUESTS,     "Please wait before requesting another OTP"),
    OTP_TOO_MANY_ATTEMPTS          (HttpStatus.BAD_REQUEST,           "OTP has been invalidated due to too many failed attempts"),
    CONSUMER_VERIFICATION_REQUIRED (HttpStatus.UNAUTHORIZED,          "Consumer verification token is missing or expired"),

    BAD_CREDENTIALS                (HttpStatus.UNAUTHORIZED,          "Invalid employee ID or password"),
    PASSWORD_RESET_REQUIRED        (HttpStatus.FORBIDDEN,             "Password change is required before continuing"),
    STAFF_ACCOUNT_DISABLED         (HttpStatus.FORBIDDEN,             "Staff account is disabled"),
    REFRESH_TOKEN_INVALID          (HttpStatus.UNAUTHORIZED,          "Refresh token is invalid or revoked"),

    EMPLOYEE_ID_TAKEN                  (HttpStatus.CONFLICT,          "Employee ID already in use"),
    ADMIN_ALREADY_EXISTS_FOR_SUBDIV    (HttpStatus.CONFLICT,          "An active admin already exists for this subdivision"),
    ENGINEER_ALREADY_EXISTS_FOR_DC     (HttpStatus.CONFLICT,          "An active engineer already exists for this distribution center"),
    STAFF_NOT_FOUND                    (HttpStatus.NOT_FOUND,         "Staff account not found"),
    STAFF_SCOPE_MISMATCH               (HttpStatus.FORBIDDEN,         "Staff account is outside your subdivision"),
    STAFF_ROLE_FIELDS_INVALID          (HttpStatus.BAD_REQUEST,       "Staff role and scope fields are inconsistent"),
    DC_NOT_IN_SUBDIVISION              (HttpStatus.CONFLICT,          "Distribution center does not belong to the chosen subdivision"),
    CANNOT_DEACTIVATE_SELF             (HttpStatus.CONFLICT,          "You cannot deactivate or reset your own account"),

    // ---------- Master data ----------
    SUBDIVISION_NOT_FOUND          (HttpStatus.NOT_FOUND,             "Subdivision not found"),
    SUBDIVISION_INACTIVE           (HttpStatus.CONFLICT,              "Subdivision is inactive"),
    SUBDIVISION_CODE_TAKEN         (HttpStatus.CONFLICT,              "Subdivision code already in use"),
    SUBDIVISION_HAS_ACTIVE_DCS     (HttpStatus.CONFLICT,              "Subdivision cannot be deactivated while it has active distribution centers"),
    SUBDIVISION_HAS_ACTIVE_STAFF   (HttpStatus.CONFLICT,              "Subdivision cannot be deactivated while staff accounts in it are still active"),
    DC_NOT_FOUND                   (HttpStatus.NOT_FOUND,             "Distribution center not found"),
    DC_INACTIVE                    (HttpStatus.CONFLICT,              "Distribution center is inactive"),
    DC_CODE_TAKEN                  (HttpStatus.CONFLICT,              "Distribution center code already in use"),
    DC_NOT_IN_SCOPE                (HttpStatus.FORBIDDEN,             "Distribution center is outside your scope"),
    DC_HAS_ACTIVE_STAFF            (HttpStatus.CONFLICT,              "Distribution center cannot be deactivated while staff accounts in it are still active"),
    CATEGORY_NOT_FOUND             (HttpStatus.NOT_FOUND,             "Complaint category not found"),
    CATEGORY_INACTIVE              (HttpStatus.CONFLICT,              "Complaint category is inactive"),
    CATEGORY_CODE_TAKEN            (HttpStatus.CONFLICT,              "Complaint category code already in use"),
    CATEGORY_HAS_OPEN_COMPLAINTS   (HttpStatus.CONFLICT,              "Complaint category cannot be deactivated while open complaints reference it"),

    // ---------- Complaint lifecycle ----------
    COMPLAINT_NOT_FOUND            (HttpStatus.NOT_FOUND,             "Complaint not found"),
    COMPLAINT_NOT_OWNED_BY_CONSUMER(HttpStatus.FORBIDDEN,             "This complaint does not belong to the verified consumer"),
    COMPLAINT_NOT_IN_SUBMITTED_STATE(HttpStatus.CONFLICT,             "Action allowed only while status is SUBMITTED"),
    COMPLAINT_INVALID_STATE_TRANSITION(HttpStatus.CONFLICT,           "Invalid status transition"),
    COMPLAINT_VERSION_CONFLICT     (HttpStatus.CONFLICT,              "Complaint was updated by someone else; please reload and retry"),
    SLA_BREACH_REASON_REQUIRED     (HttpStatus.BAD_REQUEST,           "SLA breach reason is required for late closure"),
    PARENT_COMPLAINT_NOT_FOUND     (HttpStatus.NOT_FOUND,             "Parent complaint not found for duplicate marking"),
    TECHNICIAN_NOT_IN_DC           (HttpStatus.CONFLICT,              "Technician is not assigned to the target distribution center"),
    TECHNICIAN_NOT_FOUND           (HttpStatus.NOT_FOUND,             "Technician account not found or inactive"),
    COMPLAINT_OUT_OF_SCOPE         (HttpStatus.FORBIDDEN,             "Complaint is outside your assignment scope"),
    DUPLICATE_OF_SELF              (HttpStatus.BAD_REQUEST,           "A complaint cannot be marked as a duplicate of itself"),
    DUPLICATE_PARENT_INVALID       (HttpStatus.CONFLICT,              "Parent complaint cannot itself be a duplicate or rejected"),
    NO_ACTIVE_ENGINEER_FOR_DC      (HttpStatus.CONFLICT,              "Target distribution center has no active engineer"),

    // ---------- Image upload ----------
    IMAGE_LIMIT_EXCEEDED           (HttpStatus.CONFLICT,              "Maximum number of images already uploaded"),
    IMAGE_TOO_LARGE                (HttpStatus.PAYLOAD_TOO_LARGE,     "Image exceeds the 1 MB limit"),
    IMAGE_INVALID_TYPE             (HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Only JPEG and PNG images are accepted"),
    IMAGE_NOT_FOUND                (HttpStatus.NOT_FOUND,             "Image not found"),
    IMAGE_UPLOAD_FAILED            (HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store one or more images"),

    // ---------- Feedback ----------
    FEEDBACK_ALREADY_SUBMITTED     (HttpStatus.CONFLICT,              "Feedback has already been submitted for this complaint"),
    FEEDBACK_NOT_ALLOWED_YET       (HttpStatus.CONFLICT,              "Feedback is allowed only after the complaint is closed");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() { return httpStatus; }
    public String defaultMessage() { return defaultMessage; }
}

