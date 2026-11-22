package com.iitsm.domain.exception;

// OOP (Inheritance, IS-A): PersistenceFailureException is a kind of IitsmException, so one central handler can catch it and map it to a stable error code.
/**
 * OOP: Thrown when an optimistic lock conflict, concurrency collision, or storage failure occurs.
 */
public class PersistenceFailureException extends IitsmException {

    public PersistenceFailureException(String message, Throwable cause) {
        super("ERR_PERSISTENCE_FAILURE", message, cause);
    }

    public PersistenceFailureException(String message) {
        super("ERR_PERSISTENCE_FAILURE", message);
    }
}

