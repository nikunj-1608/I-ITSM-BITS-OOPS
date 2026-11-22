package com.iitsm.web.dto;

import jakarta.validation.constraints.Size;

// OOP (Immutability): LogoutRequest is a record, so it is a read-only data carrier with no setters.
/**
 * Body of {@code POST /api/auth/logout} (ADR-047). The token is optional so that signing out is idempotent: no token,
 * an unknown token and an already ended session all answer 204. Only an oversized value is refused (400).
 */
public record LogoutRequest(
        @Size(max = 200, message = "Refresh token is too long")
        String refreshToken) {}
