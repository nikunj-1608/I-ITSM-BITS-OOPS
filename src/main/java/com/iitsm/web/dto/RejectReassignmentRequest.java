package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The administrator's reason for rejecting a technician's wrong-domain request (shown to the technician on the ticket). */
public record RejectReassignmentRequest(
        @NotBlank(message = "A reason for rejecting the request is required")
        @Size(max = 1000, message = "The reason must be at most 1000 characters")
        String reason) {}
