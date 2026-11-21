package com.iitsm.domain.simulation.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.ValidationException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bounds of an offline comparison request (ADR-044, Phase L3). */
class ComparisonParametersTest {

    private static final List<String> AVAILABLE = List.of("WEIGHTED_COMPOSITE", "SKILL_BASED", "LOAD_BASED", "EXPERIENCE_BASED", "ROUND_ROBIN");

    private static ComparisonParameters of(Long seed, Integer tickets, Integer replications, Double rate, List<String> strategies) {
        return ComparisonParameters.of(seed, tickets, replications, rate, strategies, AVAILABLE);
    }

    @Test
    @DisplayName("a request with nothing in it gets the defaults: seed 42, 500 tickets, 10 replications, 15 per hour, every strategy")
    void defaults() {
        ComparisonParameters p = of(null, null, null, null, null);

        assertThat(p.seed()).isEqualTo(42L);
        assertThat(p.ticketCount()).isEqualTo(500);
        assertThat(p.replications()).isEqualTo(10);
        assertThat(p.arrivalsPerHour()).isEqualTo(15.0);
        assertThat(p.strategyNames()).containsExactly("EXPERIENCE_BASED", "LOAD_BASED", "ROUND_ROBIN", "SKILL_BASED", "WEIGHTED_COMPOSITE");
        assertThat(p.startInstant()).isEqualTo(ComparisonParameters.DEFAULT_START);
        assertThat(of(null, null, null, null, List.of()).strategyNames()).hasSize(5);
    }

    @Test
    @DisplayName("the bounds are inclusive: 1 and 5000 tickets, 1 and 50 replications, 0.1 and 1000 per hour")
    void inclusiveBounds() {
        assertThat(of(1L, 1, 1, 0.1, null).ticketCount()).isEqualTo(1);
        assertThat(of(-7L, 5000, 5, 1000.0, null).ticketCount()).isEqualTo(5000);
        assertThat(of(0L, 500, 50, 15.0, null).replications()).isEqualTo(50);
        assertThat(of(null, 5000, 5, null, null).ticketCount() * 5).isEqualTo(25_000);
    }

    @Test
    @DisplayName("every field out of range is a validation error that names the field")
    void outOfRangeFieldsAreNamed() {
        assertField(() -> of(null, 0, null, null, null), "ticketCount");
        assertField(() -> of(null, 5001, null, null, null), "ticketCount");
        assertField(() -> of(null, -3, null, null, null), "ticketCount");
        assertField(() -> of(null, null, 0, null, null), "replications");
        assertField(() -> of(null, null, 51, null, null), "replications");
        assertField(() -> of(null, null, null, 0.0, null), "arrivalsPerHour");
        assertField(() -> of(null, null, null, 0.05, null), "arrivalsPerHour");
        assertField(() -> of(null, null, null, 1000.5, null), "arrivalsPerHour");
        assertField(() -> of(null, null, null, Double.NaN, null), "arrivalsPerHour");
        assertField(() -> of(null, null, null, Double.POSITIVE_INFINITY, null), "arrivalsPerHour");
    }

    @Test
    @DisplayName("tickets x replications above 25,000 is refused, with the product in the message")
    void productIsCapped() {
        assertThatThrownBy(() -> of(null, 5000, 6, null, null)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("ticketCount").hasMessageContaining("25000").hasMessageContaining("30000");
        assertThatThrownBy(() -> of(null, 1000, 50, null, null)).isInstanceOf(ValidationException.class);
        assertThat(of(null, 500, 50, null, null).ticketCount()).isEqualTo(500);
    }

    @Test
    @DisplayName("strategy names are trimmed, upper-cased, de-duplicated and sorted; an unknown one lists the valid names")
    void strategyNames() {
        assertThat(of(null, null, null, null, List.of(" round_robin", "skill_based", "ROUND_ROBIN")).strategyNames())
                .containsExactly("ROUND_ROBIN", "SKILL_BASED");

        assertThatThrownBy(() -> of(null, null, null, null, List.of("LOAD_BASED", "FASTEST")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("strategies").hasMessageContaining("'FASTEST'").hasMessageContaining("ROUND_ROBIN");
        assertField(() -> of(null, null, null, null, java.util.Arrays.asList("LOAD_BASED", null)), "strategies");
        assertField(() -> of(null, null, null, null, List.of(" ")), "strategies");
    }

    @Test
    @DisplayName("the canonical constructor enforces the same rules and copies the list")
    void canonicalConstructor() {
        assertField(() -> new ComparisonParameters(1, 0, 1, 15, List.of("A"), ComparisonParameters.DEFAULT_START), "ticketCount");
        assertField(() -> new ComparisonParameters(1, 10, 1, 15, List.of(), ComparisonParameters.DEFAULT_START), "strategies");
        java.util.ArrayList<String> names = new java.util.ArrayList<>(List.of("A"));
        ComparisonParameters p = new ComparisonParameters(1, 10, 1, 15, names, ComparisonParameters.DEFAULT_START);
        names.add("B");
        assertThat(p.strategyNames()).containsExactly("A");
    }

    private static void assertField(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOf(ValidationException.class).hasMessageContaining("'" + field + "'");
    }
}
