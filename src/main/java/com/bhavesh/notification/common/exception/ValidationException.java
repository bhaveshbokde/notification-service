package com.bhavesh.notification.common.exception;

import com.bhavesh.notification.common.exception.ProblemDetailFactory.FieldViolation;

import java.util.List;

/**
 * Cross-field validation failed.
 *
 * <p>Bean Validation handles single-field rules declaratively, but it cannot
 * express "the recipient must be an email address <em>when the channel is
 * EMAIL</em>" — the constraint depends on another field's value. Those rules
 * live in the service, and this exception carries their results in the same
 * shape {@code MethodArgumentNotValidException} produces, so the client sees
 * one consistent error format regardless of which layer rejected the request.
 *
 * <p>Carries a <em>list</em> rather than a single message so a request with two
 * problems reports both. Returning one at a time makes a caller fix-and-retry
 * in a loop, which is a poor API and an avoidable round trip.
 */
public class ValidationException extends ApiException {

    private final transient List<FieldViolation> violations;

    public ValidationException(List<FieldViolation> violations) {
        super(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultDetail());
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
