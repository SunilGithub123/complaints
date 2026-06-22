package com.example.complaints.auth.service;

/**
 * Thrown when an SMS provider fails to accept an OTP send. Wrapped by {@code OtpService} into
 * {@link com.example.complaints.common.exception.BusinessException} with a generic error code,
 * so the OTP itself is never echoed in the response or logs.
 */
public class SmsDeliveryException extends RuntimeException {

    public SmsDeliveryException(String message) {
        super(message);
    }

    public SmsDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}

