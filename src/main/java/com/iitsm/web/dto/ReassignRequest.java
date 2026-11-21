package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;

// OOP (Immutability): ReassignRequest is a record, so it is a read-only data carrier with no setters.
/** A team lead's request to give a ticket of the team to another technician of the team (ADR-045). */
public record ReassignRequest(
        @NotBlank(message = "Technician ID is required")
        String technicianId,

        @NotBlank(message = "A reason for the reassignment is required")
        String reason) {}
