package com.iitsm.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.TicketNumber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RegistryDepartmentCodeResolverTest {

    private final RegistryDepartmentCodeResolver resolver = new RegistryDepartmentCodeResolver();

    @ParameterizedTest
    @CsvSource({"Engineering,ENG", "Finance,FIN", "HR,HR", "Sales,SAL", "Operations,OPS",
            "Customer Support,CSU", "Legal,LEG", "Executive,EXE"})
    @DisplayName("the eight seeded departments map to their agreed codes")
    void seededDepartmentsMap(String department, String code) {
        assertThat(resolver.codeFor(department)).isEqualTo(code);
    }

    @Test
    @DisplayName("lookup ignores case and extra whitespace")
    void lookupIsNormalised() {
        assertThat(resolver.codeFor("  customer   SUPPORT ")).isEqualTo("CSU");
        assertThat(resolver.codeFor("fInAnCe")).isEqualTo("FIN");
    }

    @Test
    @DisplayName("an unregistered department falls back to its first three letters")
    void unknownDepartmentFallsBack() {
        assertThat(resolver.codeFor("Security")).isEqualTo("SEC");
        assertThat(resolver.codeFor("R&D Lab")).isEqualTo("RDL");
        assertThat(resolver.codeFor("IT")).isEqualTo("IT");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "7", "A", "42!!", "-"})
    @DisplayName("blank or letterless departments resolve to the MISC code")
    void unusableDepartmentIsGeneral(String department) {
        assertThat(resolver.codeFor(department)).isEqualTo(RegistryDepartmentCodeResolver.MISC_CODE);
    }

    @Test
    @DisplayName("every resolved code is a legal ticket-number department code")
    void resolvedCodesAreAlwaysValid() {
        String[] samples = {"Engineering", "Customer Support", "Security", "IT", "7", null, "  ",
                "Zażółć", "Ünïcödé Ops"};
        for (String sample : samples) {
            String code = resolver.codeFor(sample);
            assertThat(new TicketNumber(code, 2026, 1).format()).startsWith(code + "-");
        }
    }

    @Test
    @DisplayName("a new department can be registered without touching existing code")
    void registrationExtendsTheResolver() {
        resolver.register("Research & Development", "RND");
        assertThat(resolver.codeFor("research & development")).isEqualTo("RND");
    }

    @Test
    @DisplayName("registration overrides a fallback and an earlier mapping")
    void registrationOverrides() {
        assertThat(resolver.codeFor("Security")).isEqualTo("SEC");
        resolver.register("Security", "INFO");
        assertThat(resolver.codeFor("Security")).isEqualTo("INFO");
    }

    @Test
    @DisplayName("registration rejects blank departments and malformed codes")
    void registrationValidates() {
        assertThatThrownBy(() -> resolver.register(" ", "ABC")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> resolver.register(null, "ABC")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> resolver.register("X", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> resolver.register("X", "abc")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> resolver.register("X", "A")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> resolver.register("X", "ABCDE")).isInstanceOf(ValidationException.class);
    }
}
