package com.iitsm.domain.exception;

// OOP (Exception hierarchy): every application exception extends this unchecked base, so one handler can map them all to an error code.
/**
 * OOP: Base unchecked exception for all domain and application errors in Swiftdesk.
 * Every exception carries a machine-readable error code for consistent client mapping.
 */
public abstract class IitsmException extends RuntimeException {

    private final String errorCode;

    // OOP (Constructor overloading): with and without a cause; both feed super(message) of RuntimeException.
    protected IitsmException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected IitsmException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

