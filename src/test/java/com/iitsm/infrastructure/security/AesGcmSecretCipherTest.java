package com.iitsm.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authentication hardening, Phase S4 (ADR-048): AES-256-GCM for the authenticator secret. */
class AesGcmSecretCipherTest {

    private static String newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private final String key = newKey();
    private final AesGcmSecretCipher cipher = new AesGcmSecretCipher(key);

    @Test
    @DisplayName("a secret survives the round trip")
    void roundTrip() {
        String stored = cipher.encrypt("JBSWY3DPEHPK3PXP", "ADM-1");

        assertThat(cipher.decrypt(stored, "ADM-1")).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    @DisplayName("the stored text does not contain the secret and every encryption is different (fresh nonce)")
    void uniqueNonces() {
        Set<String> nonces = new HashSet<>();
        Set<String> outputs = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String stored = cipher.encrypt("JBSWY3DPEHPK3PXP", "ADM-1");
            byte[] bytes = Base64.getDecoder().decode(stored);
            nonces.add(Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(bytes, AesGcmSecretCipher.NONCE_BYTES)));
            outputs.add(stored);
            assertThat(stored).doesNotContain("JBSWY3DPEHPK3PXP");
        }

        assertThat(nonces).hasSize(200);
        assertThat(outputs).hasSize(200);
    }

    @Test
    @DisplayName("a changed byte anywhere (nonce, ciphertext, tag) fails the decryption")
    void tamper() {
        byte[] bytes = Base64.getDecoder().decode(cipher.encrypt("JBSWY3DPEHPK3PXP", "ADM-1"));

        for (int position = 0; position < bytes.length; position++) {
            byte[] changed = bytes.clone();
            changed[position] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(changed);

            assertThatThrownBy(() -> cipher.decrypt(tampered, "ADM-1"))
                    .as("byte %d", position).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("another key cannot decrypt")
    void wrongKey() {
        String stored = cipher.encrypt("JBSWY3DPEHPK3PXP", "ADM-1");

        assertThatThrownBy(() -> new AesGcmSecretCipher(newKey()).decrypt(stored, "ADM-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the secret is bound to its owner: the same text does not decrypt for another user id")
    void boundToTheOwner() {
        String stored = cipher.encrypt("JBSWY3DPEHPK3PXP", "ADM-1");

        assertThatThrownBy(() -> cipher.decrypt(stored, "ADM-2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(stored, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("junk or too short input is refused with a message that does not say why")
    void junk() {
        for (String junk : new String[] {"", "not base64 !!", "AAAA", Base64.getEncoder().encodeToString(new byte[12])}) {
            assertThatThrownBy(() -> cipher.decrypt(junk, "ADM-1"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("The stored secret could not be decrypted");
        }
    }

    @Test
    @DisplayName("a missing, blank, short, long or non-Base64 key stops the start-up and names the property")
    void failFastOnABadKey() {
        for (String bad : new String[] {null, "", "  ", "not base64 !!",
                Base64.getEncoder().encodeToString(new byte[16]), Base64.getEncoder().encodeToString(new byte[33])}) {
            assertThatThrownBy(() -> new AesGcmSecretCipher(bad))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("iitsm.security.mfa.encryption-key");
        }
    }

    @Test
    @DisplayName("the error for a bad key never prints the key")
    void keyIsNeverInTheMessage() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new AesGcmSecretCipher(tooShort)).message().doesNotContain(tooShort);
    }
}
