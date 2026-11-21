package com.iitsm.domain.exception;

/** OOP: Thrown when two-step verification is switched on for an account that already has it (ADR-048). HTTP 409. */
public class MfaAlreadyEnabledException extends IitsmException {

    public static final String ERROR_CODE = "ERR_MFA_ALREADY_ENABLED";

    public MfaAlreadyEnabledException() {
        super(ERROR_CODE, "Two-step verification is already enabled for this account");
    }
}
