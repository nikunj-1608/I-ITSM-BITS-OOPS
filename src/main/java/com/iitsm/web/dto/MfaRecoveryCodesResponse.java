package com.iitsm.web.dto;

import java.util.List;

/** Answer of {@code POST /api/auth/mfa/enable} (ADR-048): the recovery codes, shown once and never again. */
public record MfaRecoveryCodesResponse(List<String> recoveryCodes) {}
