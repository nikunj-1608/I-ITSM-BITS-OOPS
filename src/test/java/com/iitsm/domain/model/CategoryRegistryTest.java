package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OOP: Proves the Open/Closed Principle for incident categories.
 * Demonstrates that new categories can be introduced dynamically at runtime without modifying enums.
 */
class CategoryRegistryTest {

    @Test
    @DisplayName("CategoryRegistry initializes all 9 standard enum categories")
    void testStandardCategoriesInitialized() {
        CategoryRegistry registry = new CategoryRegistry();

        for (IncidentCategory category : IncidentCategory.values()) {
            assertThat(registry.isValid(category.name())).isTrue();
            assertThat(registry.getDisplayName(category.name())).isEqualTo(category.getDisplayName());
        }
    }

    @Test
    @DisplayName("Open/Closed: Adding a new category requires registration only, without code modification")
    void testOpenClosedCategoryRegistration() {
        CategoryRegistry registry = new CategoryRegistry();

        String newCategoryCode = "DEVOPS";
        String newCategoryName = "DevOps & CI/CD";

        assertThat(registry.isValid(newCategoryCode)).isFalse();

        // Register new category at runtime
        registry.register(newCategoryCode, newCategoryName);

        assertThat(registry.isValid(newCategoryCode)).isTrue();
        assertThat(registry.getDisplayName(newCategoryCode)).isEqualTo(newCategoryName);
        assertThat(registry.getAllCodes()).contains("DEVOPS");
    }

    @Test
    @DisplayName("CategoryRegistry rejects invalid blank registrations")
    void testInvalidRegistrations() {
        CategoryRegistry registry = new CategoryRegistry();

        assertThatThrownBy(() -> registry.register("", "Display Name"))
                .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() -> registry.register("CODE", null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("CategoryRegistry maps all standard and website domains to unique prefixes")
    void testDomainPrefixMappings() {
        CategoryRegistry registry = new CategoryRegistry();

        // Prompt specification examples
        assertThat(registry.getPrefix("Network")).isEqualTo("NET");
        assertThat(registry.getPrefix(IncidentCategory.NETWORK)).isEqualTo("NET");
        assertThat(registry.getPrefix("Software")).isEqualTo("SOF");
        assertThat(registry.getPrefix(IncidentCategory.SOFTWARE)).isEqualTo("SOF");
        assertThat(registry.getPrefix("Hardware")).isEqualTo("HWD");
        assertThat(registry.getPrefix(IncidentCategory.HARDWARE)).isEqualTo("HWD");
        assertThat(registry.getPrefix("Database")).isEqualTo("DBS");
        assertThat(registry.getPrefix(IncidentCategory.DATABASE)).isEqualTo("DBS");
        assertThat(registry.getPrefix("Security")).isEqualTo("SEC");
        assertThat(registry.getPrefix(IncidentCategory.SECURITY)).isEqualTo("SEC");

        // Remaining project & website domains
        assertThat(registry.getPrefix(IncidentCategory.CLOUD)).isEqualTo("CLD");
        assertThat(registry.getPrefix(IncidentCategory.AUTHENTICATION)).isEqualTo("AUT");
        assertThat(registry.getPrefix(IncidentCategory.APPLICATION)).isEqualTo("APP");
        assertThat(registry.getPrefix(IncidentCategory.STORAGE)).isEqualTo("STG");
        assertThat(registry.getPrefix("ACCESS")).isEqualTo("ACC");
        assertThat(registry.getPrefix("GENERAL")).isEqualTo("GEN");
        assertThat(registry.getPrefix((String) null)).isEqualTo("GEN");
    }

    @Test
    @DisplayName("CategoryRegistry prevents duplicate prefixes across different domains")
    void testPrefixUniquenessEnforced() {
        CategoryRegistry registry = new CategoryRegistry();

        // Attempting to register another domain with an existing prefix must fail
        assertThatThrownBy(() -> registry.register("NEW_NET", "New Network Domain", "NET"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Prefix NET is already assigned to domain: NETWORK");
    }
}

