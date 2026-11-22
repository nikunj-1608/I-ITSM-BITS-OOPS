package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.ValidationException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Stress-audit M5: client text that names no enum constant is a validation error (HTTP 400), never an IllegalArgumentException. */
class EnumTextTest {

    @ParameterizedTest(name = "[{index}] {0} is found by its own name")
    @EnumSource(IncidentCategory.class)
    void everyCategoryIsFoundByName(IncidentCategory category) {
        assertThat(IncidentCategory.fromText(category.name())).isEqualTo(category);
    }

    @ParameterizedTest(name = "[{index}] {0} is found by its own name")
    @EnumSource(Designation.class)
    void everyDesignationIsFoundByName(Designation designation) {
        assertThat(Designation.fromText(designation.name())).isEqualTo(designation);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" means NETWORK")
    @ValueSource(strings = {"network", "NETWORK", "  Network  ", "nEtWoRk\t"})
    void ignoresCaseAndSurroundingBlanks(String text) {
        assertThat(IncidentCategory.fromText(text)).isEqualTo(IncidentCategory.NETWORK);
    }

    @Test
    @DisplayName("An unknown name names the field, echoes the text and lists every valid value")
    void unknownNameListsTheValidValues() {
        assertThatThrownBy(() -> IncidentCategory.fromText("PRINTERS"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("category")
                .hasMessageContaining("'PRINTERS'")
                .hasMessageContaining(Stream.of(IncidentCategory.values()).map(Enum::name).collect(Collectors.joining(", ")));
        assertThatThrownBy(() -> Designation.fromText("OVERLORD"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("designation")
                .hasMessageContaining("VP_C_LEVEL");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" is not a value")
    @ValueSource(strings = {"", "   ", "\t"})
    void blankTextIsRefused(String text) {
        assertThatThrownBy(() -> IncidentCategory.fromText(text))
                .isInstanceOf(ValidationException.class).hasMessageContaining("a value is required");
    }

    @Test
    @DisplayName("Null is refused with the same error, not a NullPointerException")
    void nullIsRefused() {
        assertThatThrownBy(() -> IncidentCategory.fromText(null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("a value is required");
    }

    @Test
    @DisplayName("Long client text is cut when echoed, so an error message cannot be inflated")
    void longTextIsCut() {
        String longText = "x".repeat(500);

        assertThatThrownBy(() -> IncidentCategory.fromText(longText))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(400))
                .hasMessageContaining("...");
        assertThat(EnumText.echo("  short  ")).isEqualTo("short");
        assertThat(EnumText.echo("y".repeat(EnumText.MAX_ECHOED_LENGTH))).hasSize(EnumText.MAX_ECHOED_LENGTH);
    }
}
