package com.example.complaints.common.exception;

import com.example.complaints.common.dto.ApiResponse;
import com.example.complaints.common.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralizes the mapping from exceptions to {@link ApiResponse} payloads.
 * No stack traces ever leak in the response body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.errorCode();
        log.warn("Business error {} : {}", code.name(), ex.getMessage());
        return build(code.httpStatus(), code.name(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.name(),
                ErrorCode.VALIDATION_FAILED.defaultMessage(), fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
                details.put(v.getPropertyPath().toString(), v.getMessage()));
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.name(),
                ErrorCode.VALIDATION_FAILED.defaultMessage(), details);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, Object> details = Map.of("parameter", ex.getName(), "value", String.valueOf(ex.getValue()));
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.name(),
                "Invalid value for parameter " + ex.getName(), details);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException ex) {
        log.debug("Authentication failure: {}", ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.name(),
                ErrorCode.UNAUTHORIZED.defaultMessage(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.debug("Access denied: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN.name(),
                ErrorCode.FORBIDDEN.defaultMessage(), null);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoHandlerFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND.name(),
                "Route not found: " + ex.getRequestURL(), null);
    }

    /**
     * Spring fires this <b>before</b> {@code @RequestPart} validation when the raw multipart
     * size exceeds the servlet container limits (defaults: {@code spring.servlet.multipart.max-file-size}
     * 1 MB, {@code max-request-size} 10 MB). The {@code ComplaintImageService}'s explicit
     * {@code IMAGE_TOO_LARGE} check never gets a chance to run in this case.
     *
     * <p>Map both paths to the same {@code IMAGE_TOO_LARGE} (413) error code so the FE doesn't
     * have to disambiguate "raw multipart limit blown" vs "validated per-image size exceeded".</p>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Multipart upload exceeded servlet limit: {}", ex.getMessage());
        return build(ErrorCode.IMAGE_TOO_LARGE.httpStatus(), ErrorCode.IMAGE_TOO_LARGE.name(),
                ErrorCode.IMAGE_TOO_LARGE.defaultMessage(), null);
    }

    /**
     * Hibernate fires this when a {@code @Version}-protected entity is updated against a stale
     * row (someone else committed a newer version in between read and write). Phase 4 introduces
     * concurrent updates on {@code complaint}, so we map the framework exception to the
     * pre-allocated {@link ErrorCode#COMPLAINT_VERSION_CONFLICT} (409). The FE retry contract is
     * "reload the row and let the user reapply their action".
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic-lock conflict on {} id={}: {}",
                ex.getPersistentClassName(), ex.getIdentifier(), ex.getMessage());
        return build(ErrorCode.COMPLAINT_VERSION_CONFLICT.httpStatus(),
                ErrorCode.COMPLAINT_VERSION_CONFLICT.name(),
                ErrorCode.COMPLAINT_VERSION_CONFLICT.defaultMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAny(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name(),
                ErrorCode.INTERNAL_ERROR.defaultMessage(), null);
    }

    private ResponseEntity<ApiResponse<Void>> build(HttpStatus status, String code, String message, Map<String, Object> details) {
        Map<String, Object> safeDetails = (details == null || details.isEmpty()) ? null : new HashMap<>(details);
        ErrorResponse err = ErrorResponse.of(code, message, safeDetails);
        return ResponseEntity.status(status).body(ApiResponse.error(err));
    }
}

