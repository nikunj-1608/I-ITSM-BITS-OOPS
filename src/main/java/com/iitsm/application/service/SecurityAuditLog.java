package com.iitsm.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pattern: Facade over a dedicated logger ({@value #LOGGER_NAME}) for security events (ADR-047), so they can be routed
 * or filtered on their own. Records user ids and session (family) ids only. It never receives a password, an access
 * token, a refresh token or a one-time code, so none can be logged by mistake ({@code SecurityLogHygieneTest}).
 */
@Component
public class SecurityAuditLog {

    public static final String LOGGER_NAME = "SECURITY_AUDIT";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    public void loginSuccess(String userId, String sessionId) {
        log.info("LOGIN_SUCCESS user={} session={}", userId, sessionId);
    }

    public void refreshRotated(String userId, String sessionId) {
        log.info("REFRESH_ROTATED user={} session={}", userId, sessionId);
    }

    public void refreshReuseDetected(String userId, String sessionId) {
        log.warn("REFRESH_REUSE_DETECTED user={} session={}: the session was revoked", userId, sessionId);
    }

    public void logout(String userId, String sessionId) {
        log.info("LOGOUT user={} session={}", userId, sessionId);
    }

    public void logoutAll(String userId) {
        log.info("LOGOUT_ALL user={}", userId);
    }

    public void mfaEnabled(String userId) {
        log.info("MFA_ENABLED user={}", userId);
    }

    public void mfaDisabled(String userId) {
        log.info("MFA_DISABLED user={}", userId);
    }

    public void mfaReset(String actorId, String targetId) {
        log.warn("MFA_RESET actor={} target={}", actorId, targetId);
    }

    /** A code was not accepted. The code itself is never passed in or logged. */
    public void mfaFailed(String userId) {
        log.warn("MFA_CODE_REFUSED user={}", userId);
    }

    public void recoveryCodeUsed(String userId) {
        log.warn("MFA_RECOVERY_CODE_USED user={}", userId);
    }
}
