package com.iitsm.application.port;

/**
 * Port for encrypting secrets before they are stored (the authenticator secret, ADR-048). {@code associatedData}
 * (the owner's user id) is bound to the ciphertext: a stored secret copied to another user's row does not decrypt.
 */
public interface SecretCipherPort {

    /** Encrypts and returns text that is safe to store (it changes on every call). */
    String encrypt(String plaintext, String associatedData);

    /**
     * Decrypts text made by {@link #encrypt}.
     *
     * @throws IllegalStateException when the text was altered, belongs to other associated data or another key
     */
    String decrypt(String stored, String associatedData);
}
