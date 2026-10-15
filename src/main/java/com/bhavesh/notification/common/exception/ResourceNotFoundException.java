package com.bhavesh.notification.common.exception;

/**
 * Thrown when a resource does not exist <em>or</em> belongs to a different
 * caller.
 *
 * <p>Deliberately 404 for both cases. Returning 403 for "someone else's
 * notification" would confirm that the id exists, turning every endpoint into
 * an existence oracle an attacker can enumerate. Notification ids are
 * time-ordered UUIDs, so enumeration is already impractical — but the API
 * should not be the weak link, and the discipline costs nothing.
 *
 * <p>The repository methods used by customer-facing reads take a
 * {@code userId} in the WHERE clause, so "missing" and "not yours" are
 * genuinely indistinguishable in the code as well as in the response.
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String resource, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND, "%s %s was not found".formatted(resource, id));
    }

    public ResourceNotFoundException(String detail) {
        super(ErrorCode.RESOURCE_NOT_FOUND, detail);
    }
}
