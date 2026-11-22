package com.iitsm.domain.exception;

// OOP (Inheritance, IS-A): InvalidMfaCodeException is a kind of IitsmException, so one central handler can catch it and map it to a stable error code.
/**
 * OOP: Thrown when a one-time code or recovery code, or the challenge it belongs to, is not accepted (ADR-048). One
 * generic message for a wrong code, a replayed code, a bad recovery code, an expired challenge and a locked account, so
 * the answer says nothing about which part failed. Mapped to HTTP 401.
 */
public class InvalidMfaCodeException extends IitsmException {

    public static final String ERROR_CODE = "ERR_INVALID_MFA_CODE";
    public static final String MESSAGE = "Invalid or expired verification code";

    public InvalidMfaCodeException() {
        super(ERROR_CODE, MESSAGE);
    }
}
