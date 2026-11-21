package com.iitsm.application.port;

/**
 * Port for random opaque tokens (refresh tokens, ADR-047). The server stores only {@link #hash(String)} of a token, so a
 * database leak does not hand out usable tokens.
 */
public interface OpaqueTokenPort {

    /** A new unguessable token. */
    String newToken();

    /** The stable, non-reversible form of a token that is stored and looked up. */
    String hash(String token);
}
