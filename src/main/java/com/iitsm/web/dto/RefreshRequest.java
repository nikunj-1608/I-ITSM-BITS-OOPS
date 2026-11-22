package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// OOP (Immutability): RefreshRequest is a record, so it is a read-only data carrier with no setters.
/** Body of {@code POST /api/auth/refresh} (ADR-047). A real token is 43 characters; the cap bounds what is hashed. */
public record RefreshRequest(
        @NotBlank(message = "Refresh token is required")
        @Size(max = 200, message = "Refresh token is too long")
        String refreshToken) {}
