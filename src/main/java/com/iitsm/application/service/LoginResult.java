package com.iitsm.application.service;

import java.util.Objects;

/**
 * The outcome of the password step of a sign-in (ADR-048): either the user is signed in, or the account has
 * two-step verification and must send a code with the challenge token it was given.
 */
public sealed interface LoginResult {

    /** Signed in: the session with its access and refresh tokens. */
    record Authenticated(AuthSession session) implements LoginResult {
        public Authenticated {
            Objects.requireNonNull(session, "session cannot be null");
        }
    }

    /** The password was right; the account needs a one-time code. The token proves the password step for a few minutes. */
    record MfaRequired(String challengeToken) implements LoginResult {
        public MfaRequired {
            Objects.requireNonNull(challengeToken, "challengeToken cannot be null");
        }
    }
}
