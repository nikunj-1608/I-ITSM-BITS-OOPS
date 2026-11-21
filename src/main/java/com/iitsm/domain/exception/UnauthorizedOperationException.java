package com.iitsm.domain.exception;

// OOP (Inheritance, IS-A): a more specific kind of IitsmException; the exception type itself carries the meaning (Exception handling lecture).
/**
 * OOP: Thrown when an actor attempts an action without adequate ownership or role privileges.
 */
public class UnauthorizedOperationException extends IitsmException {

    public UnauthorizedOperationException(String message) {
        super("ERR_UNAUTHORIZED_OPERATION", message);
    }
}

