package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;

// OOP (Immutability): ProvideInfoRequest is a record, so it is a read-only data carrier with no setters.
public record ProvideInfoRequest(
        @NotBlank(message = "Response text cannot be blank")
        String responseText) {}

