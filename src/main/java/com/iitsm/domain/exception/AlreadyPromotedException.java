package com.iitsm.domain.exception;

// OOP (Inheritance, IS-A): AlreadyPromotedException is a kind of IitsmException, so one central handler can catch it and map it to a stable error code.
/** OOP: The ticket is already in the knowledge base; a ticket is promoted once (HTTP 409 {@code ERR_ALREADY_PROMOTED}, ADR-042). */
public class AlreadyPromotedException extends IitsmException {

    public static final String ERROR_CODE = "ERR_ALREADY_PROMOTED";

    public AlreadyPromotedException(String incidentId) {
        super(ERROR_CODE, "Ticket " + incidentId + " is already in the knowledge base");
    }
}
