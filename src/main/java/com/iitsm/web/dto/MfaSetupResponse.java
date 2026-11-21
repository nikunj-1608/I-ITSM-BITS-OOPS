package com.iitsm.web.dto;

// OOP (Immutability): MfaSetupResponse is a record, so it is a read-only data carrier with no setters.
/**
 * Answer of {@code POST /api/auth/mfa/setup} (ADR-048): the secret and the {@code otpauth://} URI, as text (there is no QR
 * image). Shown once; no other endpoint returns the secret.
 */
public record MfaSetupResponse(String secret, String otpauthUri) {}
