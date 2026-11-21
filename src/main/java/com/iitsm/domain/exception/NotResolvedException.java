package com.iitsm.domain.exception;

// OOP (Inheritance, IS-A): NotResolvedException is a kind of IitsmException, so one central handler can catch it and map it to a stable error code.
/**
 * OOP: The ticket has no confirmed resolution (it is not RESOLVED, VERIFIED or CLOSED, or has no resolution report), so
 * it cannot be promoted into the knowledge base (HTTP 409 {@code ERR_NOT_RESOLVED}, ADR-042). The request is well formed;
 * the ticket's state does not allow it, like the other status rules of ADR-028.
 */
public class NotResolvedException extends IitsmException {

    public static final String ERROR_CODE = "ERR_NOT_RESOLVED";

    public NotResolvedException(String incidentId, String reason) {
        super(ERROR_CODE, "Ticket " + incidentId + " cannot be promoted: " + reason);
    }
}
