package com.iitsm.web.dto;

// OOP (Immutability): AuthResponse is a record, so it is a read-only data carrier with no setters.
/**
 * Answer of sign-in, registration, refresh, the second sign-in step and {@code /me}. {@code refreshToken} and
 * {@code expiresIn} (access token lifetime in seconds) are set when a session was just started or renewed (ADR-047).
 * {@code mfaRequired} with {@code mfaToken} (and no tokens) means the password was right and a code is needed
 * (ADR-048); {@code mfaEnabled} tells the signed-in user whether their own account has two-step verification.
 */
public record AuthResponse(
        String token,
        String userId,
        String name,
        String email,
        String role,
        String refreshToken,
        Long expiresIn,
        Boolean mfaRequired,
        String mfaToken,
        Boolean mfaEnabled) {

    /** Without a session: the shape used before sessions existed. */
    public AuthResponse(String token, String userId, String name, String email, String role) {
        this(token, userId, name, email, role, null, null, null, null, null);
    }

    /** A signed-in answer with a session. */
    public AuthResponse(
            String token, String userId, String name, String email, String role, String refreshToken, Long expiresIn) {
        this(token, userId, name, email, role, refreshToken, expiresIn, false, null, null);
    }

    /** The password step passed, a code is needed: no access token and no refresh token yet. */
    public static AuthResponse mfaChallenge(String mfaToken) {
        return new AuthResponse(null, null, null, null, null, null, null, true, mfaToken, null);
    }
}
