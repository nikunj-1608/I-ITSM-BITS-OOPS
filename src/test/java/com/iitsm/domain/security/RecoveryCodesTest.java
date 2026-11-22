package com.iitsm.domain.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authentication hardening, Phase S4 (ADR-048): recovery codes. */
class RecoveryCodesTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    @DisplayName("eight codes of 10 Base32 characters, all different")
    void generate() {
        List<String> codes = RecoveryCodes.generate(8, random);

        assertThat(codes).hasSize(8);
        assertThat(codes).allMatch(code -> code.matches("[A-Z2-7]{10}"));
        assertThat(new HashSet<>(codes)).hasSize(8);
    }

    @Test
    @DisplayName("sets differ from one call to the next")
    void unpredictable() {
        assertThat(RecoveryCodes.generate(8, random)).isNotEqualTo(RecoveryCodes.generate(8, random));
    }

    @Test
    @DisplayName("the list cannot be changed by the caller")
    void immutable() {
        List<String> codes = RecoveryCodes.generate(2, random);

        assertThatThrownBy(() -> codes.add("X")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("at least one code is required")
    void countMustBePositive() {
        assertThatThrownBy(() -> RecoveryCodes.generate(0, random)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("normalising ignores case, spaces and hyphens")
    void normalize() {
        assertThat(RecoveryCodes.normalize("abcde-fghij")).isEqualTo("ABCDEFGHIJ");
        assertThat(RecoveryCodes.normalize(" abc de fgh ij ")).isEqualTo("ABCDEFGHIJ");
        assertThat(RecoveryCodes.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("a recovery code is told from a 6-digit one-time code by its shape")
    void shape() {
        assertThat(RecoveryCodes.looksLikeRecoveryCode("ABCDEFGHIJ")).isTrue();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("abcde-fghij")).isTrue();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("234567AAAA")).isTrue();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("123456")).isFalse();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("ABCDEFGHI")).as("9 characters").isFalse();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("ABCDEFGHIJK")).as("11 characters").isFalse();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("ABCDEFGHI1")).as("1 is not in the alphabet").isFalse();
        assertThat(RecoveryCodes.looksLikeRecoveryCode(null)).isFalse();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("")).isFalse();
    }
}
