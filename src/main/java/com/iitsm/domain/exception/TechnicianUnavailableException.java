package com.iitsm.domain.exception;

// OOP (Inheritance, IS-A): TechnicianUnavailableException is a kind of IitsmException, so one central handler can catch it and map it to a stable error code.
/**
 * OOP: Thrown when no eligible technician is online or within capacity.
 */
public class TechnicianUnavailableException extends IitsmException {

    public TechnicianUnavailableException(String message) {
        super("ERR_TECHNICIAN_UNAVAILABLE", message);
    }
}

