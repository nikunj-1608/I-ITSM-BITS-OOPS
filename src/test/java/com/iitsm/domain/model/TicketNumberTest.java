package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TicketNumberTest {

    @Test
    @DisplayName("formats department, year and zero-padded sequence")
    void formatsPaddedSequence() {
        assertThat(new TicketNumber("FIN", 2026, 142).format()).isEqualTo("FIN-2026-000142");
        assertThat(new TicketNumber("HR", 2026, 1)).hasToString("HR-2026-000001");
    }

    @Test
    @DisplayName("a sequence wider than the padding is never truncated")
    void wideSequenceIsKept() {
        assertThat(new TicketNumber("OPS", 2027, 1_234_567).format()).isEqualTo("OPS-2027-1234567");
    }

    @Test
    @DisplayName("parse is the inverse of format")
    void parseRoundTrips() {
        TicketNumber original = new TicketNumber("ENG", 2026, 207);
        assertThat(TicketNumber.parse(original.format())).isEqualTo(original);
        assertThat(TicketNumber.parse("OPS-2027-1234567").sequence()).isEqualTo(1_234_567L);
    }

    @Test
    @DisplayName("parse ignores surrounding blanks and letter case")
    void parseIsForgiving() {
        assertThat(TicketNumber.parse("  fin-2026-000142 ").format()).isEqualTo("FIN-2026-000142");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "INC-3F9A21BC", "FIN-26-000142", "FIN-2026-142",
            "F-2026-000142", "FINAN-2026-000142", "FIN2026000142", "FIN-2026-000142-X",
            "FIN-2026-000000"})
    @DisplayName("parse rejects malformed text, legacy ids and a zero sequence")
    void parseRejectsMalformed(String text) {
        assertThatThrownBy(() -> TicketNumber.parse(text)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("parse rejects null and a sequence that overflows a long")
    void parseRejectsNullAndOverflow() {
        assertThatThrownBy(() -> TicketNumber.parse(null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TicketNumber.parse("FIN-2026-99999999999999999999"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("isValid distinguishes new-style numbers from legacy and garbage")
    void isValidChecksFormat() {
        assertThat(TicketNumber.isValid("FIN-2026-000142")).isTrue();
        assertThat(TicketNumber.isValid("fin-2026-000142")).isTrue();
        assertThat(TicketNumber.isValid("INC-3F9A21BC")).isFalse();
        assertThat(TicketNumber.isValid("")).isFalse();
        assertThat(TicketNumber.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("constructor enforces the invariants")
    void constructorEnforcesInvariants() {
        assertThatThrownBy(() -> new TicketNumber(null, 2026, 1))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new TicketNumber("fin", 2026, 1))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new TicketNumber("F", 2026, 1))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new TicketNumber("FINAN", 2026, 1))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new TicketNumber("FIN", 999, 1))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new TicketNumber("FIN", 10000, 1))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new TicketNumber("FIN", 2026, 0))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("value equality")
    void equalityIsByValue() {
        assertThat(new TicketNumber("FIN", 2026, 5)).isEqualTo(TicketNumber.parse("FIN-2026-000005"));
        assertThat(new TicketNumber("FIN", 2026, 5)).isNotEqualTo(new TicketNumber("FIN", 2027, 5));
    }
}
