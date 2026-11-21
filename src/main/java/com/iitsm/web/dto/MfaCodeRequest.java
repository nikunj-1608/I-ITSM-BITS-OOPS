package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/auth/mfa/enable} (ADR-048): the first one-time code from the authenticator app. */
public record MfaCodeRequest(
        @NotBlank(message = "Code is required")
        @Size(max = 32, message = "Code is too long")
        String code) {}
