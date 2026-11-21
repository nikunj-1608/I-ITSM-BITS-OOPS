package com.iitsm.domain.exception;

/**
 * OOP: Thrown when an action needs two-step verification that the account does not have, or a setup that was not
 * started (ADR-048). HTTP 409.
 */
public class MfaNotEnrolledException extends IitsmException {

    public static final String ERROR_CODE = "ERR_MFA_NOT_ENROLLED";

    public MfaNotEnrolledException(String message) {
        super(ERROR_CODE, message);
    }
}
