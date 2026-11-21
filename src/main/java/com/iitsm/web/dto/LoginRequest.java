package com.iitsm.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// OOP (Immutability): LoginRequest is a record, so it is a read-only data carrier with no setters.
public record LoginRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Password is required")
        String password) {}

