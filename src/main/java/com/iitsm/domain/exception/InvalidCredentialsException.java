package com.iitsm.domain.exception;

// OOP (Inheritance, IS-A): InvalidCredentialsException is a kind of IitsmException, so one central handler can catch it and map it to a stable error code.
/**
 * OOP: Thrown when authentication fails due to bad credentials or account lockout.
 */
public class InvalidCredentialsException extends IitsmException {

    public InvalidCredentialsException(String message) {
        super("ERR_INVALID_CREDENTIALS", message);
    }
}

