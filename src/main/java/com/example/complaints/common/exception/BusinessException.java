package com.example.complaints.common.exception;

import java.util.Map;

/**
 * The single exception type thrown by services for any business failure. See TECHNICAL_DESIGN.md §16.4.
 *
 * <p>Never throw a raw {@link RuntimeException} for a business condition — always create a new
 * {@link ErrorCode} entry first and throw {@code new BusinessException(ErrorCode.X, args...)}.</p>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null, null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        this(errorCode, message, details, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode errorCode() { return errorCode; }
    public Map<String, Object> details() { return details; }
}

