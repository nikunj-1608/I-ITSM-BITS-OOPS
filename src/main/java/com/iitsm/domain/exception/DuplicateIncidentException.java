package com.iitsm.domain.exception;

// OOP (Inheritance, IS-A): a more specific kind of IitsmException; the exception type itself carries the meaning (Exception handling lecture).
/**
 * OOP: Thrown when an exact duplicate incident is submitted by the same reporter in rapid succession.
 */
public class DuplicateIncidentException extends IitsmException {

    public DuplicateIncidentException(String existingIncidentId, String reporterId) {
        super(
                "ERR_DUPLICATE_INCIDENT",
                String.format(
                        "Duplicate incident detected: Reporter '%s' already submitted an identical ticket (%s)",
                        reporterId, existingIncidentId));
    }
}

