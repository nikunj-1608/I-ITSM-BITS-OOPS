package com.iitsm.domain.exception;

import com.iitsm.domain.model.IncidentStatus;

// OOP (Inheritance, IS-A): NotInTriageException is a kind of IitsmException, so one central handler can catch it and map it to a stable error code.
/**
 * OOP: Thrown when an administrator accepts the triage suggestion of a ticket that is not in the triage queue (it is
 * waiting for the dispatcher, already worked on, or finished). Only a ticket held back for a human decision may be
 * decided this way (ADR-028). Mapped to HTTP 409.
 */
public class NotInTriageException extends IitsmException {

    public static final String ERROR_CODE = "ERR_NOT_IN_TRIAGE";

    public NotInTriageException(String incidentId, IncidentStatus status) {
        super(ERROR_CODE, String.format(
                "Incident '%s' is not in the triage queue (status %s); only a ticket in triage can accept a suggestion",
                incidentId, status));
    }
}
