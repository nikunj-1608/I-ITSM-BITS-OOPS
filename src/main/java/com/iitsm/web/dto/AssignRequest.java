package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;

// OOP (Immutability): AssignRequest is a record, so it is a read-only data carrier with no setters.
public record AssignRequest(
        @NotBlank(message = "Technician ID is required")
        String technicianId) {}

