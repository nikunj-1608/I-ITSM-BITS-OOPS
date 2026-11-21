package com.iitsm.domain.exception;

/**
 * OOP: Thrown when a refresh token cannot be used (ADR-047). One generic message for a token that is unknown, expired,
 * revoked or replayed, so a client cannot tell which tokens exist. Mapped to HTTP 401.
 */
public class InvalidRefreshTokenException extends IitsmException {

    public static final String ERROR_CODE = "ERR_INVALID_REFRESH_TOKEN";
    public static final String MESSAGE = "Invalid or expired refresh token";

    public InvalidRefreshTokenException() {
        super(ERROR_CODE, MESSAGE);
    }
}
