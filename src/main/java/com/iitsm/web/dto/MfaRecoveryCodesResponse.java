package com.iitsm.web.dto;

import java.util.List;

// OOP (Immutability): MfaRecoveryCodesResponse is a record, so it is a read-only data carrier with no setters.
/** Answer of {@code POST /api/auth/mfa/enable} (ADR-048): the recovery codes, shown once and never again. */
public record MfaRecoveryCodesResponse(List<String> recoveryCodes) {}
