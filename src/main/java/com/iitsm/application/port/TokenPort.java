package com.iitsm.application.port;

import com.iitsm.domain.model.User;
import java.util.Optional;

/**
 * Port for authentication token issuance and validation.
 */
public interface TokenPort {
    String generateToken(User user);

    /**
     * An access token that also names the session it belongs to ({@code sid}, ADR-047), so revoking the session
     * revokes the token. The default ignores the session id, for implementations written before sessions existed.
     */
    default String generateToken(User user, String sessionId) {
        return generateToken(user);
    }

    /** The session id carried by the token, or null for a token without one (still accepted until it expires). */
    default String getSessionId(String token) {
        return null;
    }

    /**
     * A short-lived token that proves the password step of a sign-in to an account with two-step verification
     * (ADR-048). It is not an access token: it carries {@code purpose=mfa}, and the security filter never accepts it
     * as a credential.
     */
    default String generateMfaChallenge(User user) {
        throw new UnsupportedOperationException("This token port does not issue two-step verification challenges");
    }

    /** The user id a valid, unexpired challenge token was issued for; empty for anything else, access tokens included. */
    default Optional<String> parseMfaChallenge(String token) {
        return Optional.empty();
    }

    /** How long an access token lives, in seconds; 0 when unknown. */
    default long getAccessTtlSeconds() {
        return 0;
    }
    String getEmailFromToken(String token);
    boolean validateToken(String token);
}

