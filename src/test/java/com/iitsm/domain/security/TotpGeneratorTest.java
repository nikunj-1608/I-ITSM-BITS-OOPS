package com.iitsm.domain.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Authentication hardening, Phase S4 (ADR-048): RFC 6238 with the test vectors of its Appendix B (HMAC-SHA-1, the ASCII
 * secret "12345678901234567890"), the verification window and the refusal of a replayed step.
 */
class TotpGeneratorTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final Instant T = Instant.parse("2026-11-20T10:00:10Z");

    @ParameterizedTest(name = "T = {0} s gives {1} with 8 digits and {2} with 6")
    @CsvSource({
            "59,94287082,287082",
            "1111111109,07081804,081804",
            "1111111111,14050471,050471",
            "1234567890,89005924,005924",
            "2000000000,69279037,279037",
            "20000000000,65353130,353130"})
    @DisplayName("RFC 6238 Appendix B vectors (SHA-1)")
    void rfcVectors(long seconds, String eightDigits, String sixDigits) {
        long step = TotpGenerator.stepAt(Instant.ofEpochSecond(seconds));

        assertThat(TotpGenerator.codeAt(RFC_SECRET, step, 8)).isEqualTo(eightDigits);
        assertThat(TotpGenerator.codeAt(RFC_SECRET, step)).isEqualTo(sixDigits);
    }

    @Test
    @DisplayName("a step lasts 30 seconds: 29 and 30 seconds of the epoch are in different steps")
    void stepBoundaries() {
        assertThat(TotpGenerator.stepAt(Instant.ofEpochSecond(0))).isZero();
        assertThat(TotpGenerator.stepAt(Instant.ofEpochSecond(29))).isZero();
        assertThat(TotpGenerator.stepAt(Instant.ofEpochSecond(30))).isEqualTo(1);
        assertThat(TotpGenerator.stepAt(Instant.ofEpochSecond(59))).isEqualTo(1);
    }

    @Test
    @DisplayName("the code of the current step, and of one step either side, is accepted with a window of 1")
    void windowOfOneStep() {
        long now = TotpGenerator.stepAt(T);

        for (long offset = -1; offset <= 1; offset++) {
            String code = TotpGenerator.codeAt(RFC_SECRET, now + offset);
            assertThat(TotpGenerator.verify(RFC_SECRET, code, T, 1, Long.MIN_VALUE)).hasValue(now + offset);
        }
    }

    @Test
    @DisplayName("two steps either side are refused with a window of 1, accepted with a window of 2")
    void windowOfTwoStepsIsRefused() {
        long now = TotpGenerator.stepAt(T);
        String early = TotpGenerator.codeAt(RFC_SECRET, now - 2);
        String late = TotpGenerator.codeAt(RFC_SECRET, now + 2);

        assertThat(TotpGenerator.verify(RFC_SECRET, early, T, 1, Long.MIN_VALUE)).isEmpty();
        assertThat(TotpGenerator.verify(RFC_SECRET, late, T, 1, Long.MIN_VALUE)).isEmpty();
        assertThat(TotpGenerator.verify(RFC_SECRET, early, T, 2, Long.MIN_VALUE)).hasValue(now - 2);
        assertThat(TotpGenerator.verify(RFC_SECRET, late, T, 2, Long.MIN_VALUE)).hasValue(now + 2);
    }

    @Test
    @DisplayName("a window of 0 accepts only the current step")
    void windowOfZero() {
        long now = TotpGenerator.stepAt(T);

        assertThat(TotpGenerator.verify(RFC_SECRET, TotpGenerator.codeAt(RFC_SECRET, now), T, 0, Long.MIN_VALUE))
                .hasValue(now);
        assertThat(TotpGenerator.verify(RFC_SECRET, TotpGenerator.codeAt(RFC_SECRET, now - 1), T, 0, Long.MIN_VALUE))
                .isEmpty();
    }

    @Test
    @DisplayName("replay: the step just accepted, and any older one, is refused; a newer one is accepted")
    void replayIsRefused() {
        long now = TotpGenerator.stepAt(T);
        String current = TotpGenerator.codeAt(RFC_SECRET, now);

        assertThat(TotpGenerator.verify(RFC_SECRET, current, T, 1, now)).as("same step").isEmpty();
        assertThat(TotpGenerator.verify(RFC_SECRET, current, T, 1, now + 1)).as("older step").isEmpty();
        assertThat(TotpGenerator.verify(RFC_SECRET, current, T, 1, now - 1)).as("newer step").hasValue(now);
        assertThat(TotpGenerator.verify(RFC_SECRET, TotpGenerator.codeAt(RFC_SECRET, now - 1), T, 1, now))
                .as("an older code after a newer one was used").isEmpty();
    }

    @Test
    @DisplayName("a wrong, empty, null, too long or non-numeric code is refused")
    void wrongCodes() {
        long now = TotpGenerator.stepAt(T);
        String right = TotpGenerator.codeAt(RFC_SECRET, now);
        String wrong = right.equals("000000") ? "000001" : "000000";

        assertThat(TotpGenerator.verify(RFC_SECRET, wrong, T, 1, Long.MIN_VALUE)).isEmpty();
        assertThat(TotpGenerator.verify(RFC_SECRET, "", T, 1, Long.MIN_VALUE)).isEmpty();
        assertThat(TotpGenerator.verify(RFC_SECRET, null, T, 1, Long.MIN_VALUE)).isEmpty();
        assertThat(TotpGenerator.verify(RFC_SECRET, right + "0", T, 1, Long.MIN_VALUE)).isEmpty();
        assertThat(TotpGenerator.verify(RFC_SECRET, "abcdef", T, 1, Long.MIN_VALUE)).isEmpty();
        assertThat(TotpGenerator.verify(RFC_SECRET, " " + right, T, 1, Long.MIN_VALUE)).as("no trimming here").isEmpty();
    }

    @Test
    @DisplayName("another secret gives other codes")
    void otherSecret() {
        long now = TotpGenerator.stepAt(T);
        byte[] other = "ABCDEFGHIJKLMNOPQRST".getBytes(StandardCharsets.US_ASCII);

        assertThat(TotpGenerator.verify(other, TotpGenerator.codeAt(RFC_SECRET, now), T, 0, Long.MIN_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("codes keep their leading zeros and always have 6 digits")
    void leadingZeros() {
        for (long step = 0; step < 2_000; step++) {
            assertThat(TotpGenerator.codeAt(RFC_SECRET, step)).matches("\\d{6}");
        }
    }

    @Test
    @DisplayName("the otpauth URI carries the secret, the issuer and the account, encoded")
    void otpauthUri() {
        String uri = TotpGenerator.otpauthUri("Nimbus FinServe I-ITSM", "admin@nimbusfinserve.com", "JBSWY3DPEHPK3PXP");

        assertThat(uri).isEqualTo("otpauth://totp/Nimbus%20FinServe%20I-ITSM:admin%40nimbusfinserve.com"
                + "?secret=JBSWY3DPEHPK3PXP&issuer=Nimbus%20FinServe%20I-ITSM&algorithm=SHA1&digits=6&period=30");
    }

    @Test
    @DisplayName("invalid arguments are refused")
    void invalidArguments() {
        assertThatThrownBy(() -> TotpGenerator.codeAt(RFC_SECRET, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TotpGenerator.verify(RFC_SECRET, "123456", T, -1, Long.MIN_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
