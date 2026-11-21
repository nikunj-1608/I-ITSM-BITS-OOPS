package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/auth/mfa/disable} (ADR-048): turning it off needs the password and a valid code. */
public record MfaDisableRequest(
        @NotBlank(message = "Password is required")
        @Size(max = 200, message = "Password is too long")
        String password,

        @NotBlank(message = "Code is required")
        @Size(max = 32, message = "Code is too long")
        String code) {}
