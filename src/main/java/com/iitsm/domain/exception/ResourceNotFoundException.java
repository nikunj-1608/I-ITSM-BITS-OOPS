package com.iitsm.domain.exception;

// OOP (Inheritance, IS-A): ResourceNotFoundException is a kind of IitsmException, so one central handler can catch it and map it to a stable error code.
/**
 * OOP: A thing the client named does not exist (HTTP 404 {@code ERR_NOT_FOUND}), for example the source of a
 * suggestion vote (ADR-041). The code is the one the web layer already uses for an unknown path.
 */
public class ResourceNotFoundException extends IitsmException {

    public static final String ERROR_CODE = "ERR_NOT_FOUND";

    public ResourceNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
