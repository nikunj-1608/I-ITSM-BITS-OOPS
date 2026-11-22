package com.iitsm.infrastructure.security;

import com.iitsm.application.port.SecretCipherPort;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// OOP (Inheritance / Realization): AesGcmSecretCipher extends or implements a framework or port type, so it plugs into the framework without changing callers.
/**
 * Pattern: Adapter (of {@link SecretCipherPort}). AES-256-GCM with a fresh random 12-byte nonce for every encryption;
 * the stored text is Base64 of {@code nonce || ciphertext || tag}. GCM authenticates, so a changed byte, another key or
 * other associated data all fail to decrypt instead of returning garbage.
 *
 * <p>The key ({@code iitsm.security.mfa.encryption-key}, Base64 of exactly 32 bytes) is checked when the bean is
 * created, so a missing or malformed key stops the start-up (the prod profile has no default, like the JWT secret).
 */
@Component
public class AesGcmSecretCipher implements SecretCipherPort {

    static final int KEY_BYTES = 32;
    static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PROPERTY = "iitsm.security.mfa.encryption-key";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSecretCipher(@Value("${iitsm.security.mfa.encryption-key:}") String base64Key) {
        this.key = new SecretKeySpec(decodeKey(base64Key), "AES");
    }

    @Override
    public String encrypt(String plaintext, String associatedData) {
        Objects.requireNonNull(plaintext, "plaintext cannot be null");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, associatedData);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The secret could not be encrypted", e);
        }
    }

    @Override
    public String decrypt(String stored, String associatedData) {
        Objects.requireNonNull(stored, "stored cannot be null");
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            if (all.length <= NONCE_BYTES) {
                throw new IllegalStateException("The stored secret could not be decrypted");
            }
            byte[] nonce = java.util.Arrays.copyOfRange(all, 0, NONCE_BYTES);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, nonce, associatedData);
            byte[] plain = cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // no detail on purpose: the message must not help to tell a wrong key from a changed byte
            throw new IllegalStateException("The stored secret could not be decrypted");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String associatedData) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(Objects.requireNonNullElse(associatedData, "").getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(PROPERTY + " must be set (Base64 of 32 random bytes, set MFA_ENCRYPTION_KEY)");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PROPERTY + " is not valid Base64");
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException(PROPERTY + " must be exactly " + KEY_BYTES + " bytes (256 bits) after Base64 decoding");
        }
        return decoded;
    }
}
