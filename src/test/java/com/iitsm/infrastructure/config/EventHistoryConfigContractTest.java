package com.iitsm.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Remaining-defects plan, Phase I (ADR-038, stress-audit item P4): what the shipped {@code application.yml} says about
 * the in-memory event history. Production keeps none; the other profiles keep a bounded buffer. The file is read
 * directly because the test profile has its own {@code application.yml}.
 */
class EventHistoryConfigContractTest {

    private static final String MAIN_YML = "src/main/resources/application.yml";

    private static List<PropertySource<?>> documents() throws IOException {
        return new YamlPropertySourceLoader().load("main", new FileSystemResource(MAIN_YML));
    }

    private static Optional<PropertySource<?>> profile(String name) throws IOException {
        return documents().stream()
                .filter(source -> name.equals(source.getProperty("spring.config.activate.on-profile")))
                .findFirst();
    }

    @Test
    @DisplayName("P4: the base configuration keeps a bounded history of 1000 events, changeable by environment variable")
    void defaultIsBounded() throws IOException {
        PropertySource<?> base = documents().stream()
                .filter(source -> source.getProperty("spring.config.activate.on-profile") == null)
                .findFirst().orElseThrow();

        assertThat(base.getProperty("iitsm.events.history-limit")).isEqualTo("${EVENT_HISTORY_LIMIT:1000}");
    }

    @Test
    @DisplayName("P4: the prod profile keeps no event history at all")
    void prodKeepsNone() throws IOException {
        assertThat(profile("prod").orElseThrow().getProperty("iitsm.events.history-limit")).isEqualTo("${EVENT_HISTORY_LIMIT:0}");
    }
}
