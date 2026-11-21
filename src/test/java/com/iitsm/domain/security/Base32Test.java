package com.iitsm.domain.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Authentication hardening, Phase S4 (ADR-048): Base32 against the test vectors of RFC 4648 section 10. */
class Base32Test {

    @ParameterizedTest(name = "\"{0}\" encodes to \"{1}\"")
    @CsvSource(value = {
            "'',''",
            "f,MY======",
            "fo,MZXQ====",
            "foo,MZXW6===",
            "foob,MZXW6YQ=",
            "fooba,MZXW6YTB",
            "foobar,MZXW6YTBOI======"})
    @DisplayName("RFC 4648 vectors: encode with padding, and decode back")
    void rfcVectors(String plain, String encoded) {
        byte[] bytes = plain.getBytes(StandardCharsets.US_ASCII);

        assertThat(Base32.encode(bytes)).isEqualTo(encoded);
        assertThat(Base32.decode(encoded)).isEqualTo(bytes);
    }

    @Test
    @DisplayName("without padding the same text, minus the = signs")
    void withoutPadding() {
        assertThat(Base32.encodeWithoutPadding("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(Base32.encodeWithoutPadding("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
    }

    @Test
    @DisplayName("decoding accepts lower case and missing padding")
    void lenientDecoding() {
        assertThat(Base32.decode("mzxw6ytboi")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base32.decode("MZXW6")).isEqualTo("foo".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("a character outside the alphabet is refused")
    void invalidCharacter() {
        assertThatThrownBy(() -> Base32.decode("MZXW1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base32.decode("MZ XW")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base32.decode("MZXW8")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("any 20-byte secret survives the round trip, and its text is 32 characters")
    void roundTripOfRandomSecrets() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 200; i++) {
            byte[] secret = TotpGenerator.newSecret(random);
            String text = Base32.encodeWithoutPadding(secret);

            assertThat(text).hasSize(32).matches("[A-Z2-7]{32}");
            assertThat(Base32.decode(text)).isEqualTo(secret);
        }
    }
}
