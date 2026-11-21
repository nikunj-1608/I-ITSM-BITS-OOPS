package com.iitsm.domain.exception;

import com.iitsm.domain.model.ReassignmentRequest;

/**
 * OOP: Thrown when an administrator approves or rejects a reassignment request that has already been decided
 * (ADR-029). Mapped to HTTP 409.
 */
public class ReassignmentRequestNotPendingException extends IitsmException {

    public static final String ERROR_CODE = "ERR_REQUEST_NOT_PENDING";

    public ReassignmentRequestNotPendingException(String requestId, ReassignmentRequest.Status status) {
        super(ERROR_CODE, String.format("Reassignment request '%s' was already decided (%s)", requestId, status));
    }
}
