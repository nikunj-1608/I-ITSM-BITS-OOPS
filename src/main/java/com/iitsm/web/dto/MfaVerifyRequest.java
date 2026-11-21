package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// OOP (Immutability): MfaVerifyRequest is a record, so it is a read-only data carrier with no setters.
/** Body of {@code POST /api/auth/mfa/verify} (ADR-048): the challenge from the password step and a code. */
public record MfaVerifyRequest(
        @NotBlank(message = "Challenge token is required")
        @Size(max = 2000, message = "Challenge token is too long")
        String mfaToken,

        @NotBlank(message = "Code is required")
        @Size(max = 32, message = "Code is too long")
        String code) {}
