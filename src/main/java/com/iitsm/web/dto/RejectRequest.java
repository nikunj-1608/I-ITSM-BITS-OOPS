package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;

// OOP (Immutability): RejectRequest is a record, so it is a read-only data carrier with no setters.
public record RejectRequest(
        @NotBlank(message = "Rejection reason cannot be blank")
        String reason) {}

