package com.iitsm.domain.strategy.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Stress-audit M5: switching to a strategy that does not exist is a validation error (HTTP 400), and nothing changes. */
class AssignmentStrategyRegistryInputTest {

    private AssignmentStrategyRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AssignmentStrategyRegistry();
    }

    @Test
    @DisplayName("An unknown strategy name names the field, echoes the name and lists the valid strategies")
    void unknownNameListsTheValidStrategies() {
        String activeBefore = registry.getActiveStrategyName();
        String validName = registry.getAllStrategies().get(0).getStrategyName();

        assertThatThrownBy(() -> registry.setActiveStrategy("NOPE"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("strategyName")
                .hasMessageContaining("'NOPE'")
                .hasMessageContaining(validName.toUpperCase());
        assertThat(registry.getActiveStrategyName()).as("a refused switch changes nothing").isEqualTo(activeBefore);
    }

    @Test
    @DisplayName("A missing name is refused the same way, not with a NullPointerException")
    void nullNameIsRefused() {
        assertThatThrownBy(() -> registry.setActiveStrategy(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("A valid name is accepted in any case and with surrounding blanks")
    void validNameIsAccepted() {
        String name = registry.getAllStrategies().get(0).getStrategyName();

        registry.setActiveStrategy("  " + name.toLowerCase() + "  ");

        assertThat(registry.getActiveStrategyName()).isEqualTo(name.toUpperCase());
    }
}
