package com.iitsm.web.dto;

/**
 * Answer of {@code POST /api/auth/mfa/setup} (ADR-048): the secret and the {@code otpauth://} URI, as text (there is no QR
 * image). Shown once; no other endpoint returns the secret.
 */
public record MfaSetupResponse(String secret, String otpauthUri) {}
