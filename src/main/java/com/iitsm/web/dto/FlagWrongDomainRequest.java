package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;

// OOP (Immutability): FlagWrongDomainRequest is a record, so it is a read-only data carrier with no setters.
public record FlagWrongDomainRequest(
        @NotBlank(message = "Reason for wrong domain flag must be provided")
        String reason) {}

