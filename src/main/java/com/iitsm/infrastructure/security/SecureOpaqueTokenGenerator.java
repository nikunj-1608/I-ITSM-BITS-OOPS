package com.iitsm.infrastructure.security;

import com.iitsm.application.port.OpaqueTokenPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;

// OOP (Inheritance / Realization): SecureOpaqueTokenGenerator extends or implements a framework or port type, so it plugs into the framework without changing callers.
/**
 * Pattern: Adapter (of {@link OpaqueTokenPort}). A token is 32 bytes from {@link SecureRandom} (256 bits), written as
 * Base64url without padding (43 characters); its hash is SHA-256 in lower-case hex (64 characters). A fast hash is
 * enough here because the input is random, not a password: there is nothing to guess.
 */
@Component
public class SecureOpaqueTokenGenerator implements OpaqueTokenPort {

    static final int TOKEN_BYTES = 32;
    private static final String HASH_ALGORITHM = "SHA-256";

    private final SecureRandom random = new SecureRandom();

    @Override
    public String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String hash(String token) {
        Objects.requireNonNull(token, "token cannot be null");
        try {
            byte[] digest = MessageDigest.getInstance(HASH_ALGORITHM).digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " is required on every Java platform", e);
        }
    }
}
