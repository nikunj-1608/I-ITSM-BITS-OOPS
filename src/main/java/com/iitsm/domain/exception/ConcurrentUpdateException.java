package com.iitsm.domain.exception;

// OOP (Inheritance, IS-A): a more specific kind of IitsmException; the exception type itself carries the meaning (Exception handling lecture).
/**
 * OOP: Thrown when a write loses a race with another write to the same record (optimistic locking, ADR-017).
 * Mapped to HTTP 409; the message is safe to show to users and carries no entity internals.
 */
public class ConcurrentUpdateException extends IitsmException {

    public static final String ERROR_CODE = "ERR_CONCURRENT_MODIFICATION";
    public static final String USER_MESSAGE =
            "This record was changed by someone else at the same time. Reload and try again.";

    public ConcurrentUpdateException(Throwable cause) {
        super(ERROR_CODE, USER_MESSAGE, cause);
    }
}
