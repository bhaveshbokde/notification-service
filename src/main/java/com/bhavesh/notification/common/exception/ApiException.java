package com.bhavesh.notification.common.exception;

/**
 * Base class for every error this application raises deliberately.
 *
 * <p>Carrying an {@link ErrorCode} lets the exception handler derive the HTTP
 * status and the response code from the exception itself, instead of
 * maintaining a parallel mapping table that would drift.
 *
 * <p>Extends {@link RuntimeException} on purpose: Spring's declarative
 * transaction management rolls back on unchecked exceptions by default, so a
 * failing business rule aborts its transaction with no {@code rollbackFor}
 * tuning.
 */
public class ApiException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultDetail());
    }

    public ApiException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
