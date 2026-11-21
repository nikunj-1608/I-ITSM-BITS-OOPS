package com.iitsm.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The production default of the service-experience weight is written in two places (ADR-044, Phase L4): the shipped
 * {@code application.yml} and the fallback of the {@code @Value} in {@link IntelligenceConfig}. The test profile has its own
 * {@code application.yml}, so tests see only the fallback; this pins that both say the same, and that it is the weight the
 * offline simulation chose (0.10).
 */
class ServiceExperienceConfigContractTest {

    private static final Path SHIPPED_YAML = Path.of("src/main/resources/application.yml");
    private static final Path CONFIG_SOURCE = Path.of("src/main/java/com/iitsm/infrastructure/config/IntelligenceConfig.java");
    private static final String CHOSEN_WEIGHT = "0.10";

    private static String firstGroup(Path file, String regex) throws IOException {
        Matcher matcher = Pattern.compile(regex).matcher(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(matcher.find()).as("%s in %s", regex, file).isTrue();
        return matcher.group(1);
    }

    @Test
    @DisplayName("the shipped yml and the code fallback both default to the weight the simulation chose, and the full-score default agrees")
    void bothDefaultsAgree() throws IOException {
        String yaml = firstGroup(SHIPPED_YAML, "service-experience-weight: \\$\\{ASSIGNMENT_SERVICE_EXPERIENCE_WEIGHT:([0-9.]+)\\}");
        String code = firstGroup(CONFIG_SOURCE, "iitsm\\.assignment\\.service-experience-weight:([0-9.]+)\\}");
        String yamlFull = firstGroup(SHIPPED_YAML,
                "service-experience-full-score-tickets: \\$\\{ASSIGNMENT_SERVICE_EXPERIENCE_FULL_SCORE_TICKETS:([0-9]+)\\}");
        String codeFull = firstGroup(CONFIG_SOURCE, "iitsm\\.assignment\\.service-experience-full-score-tickets:([0-9]+)\\}");

        assertThat(yaml).isEqualTo(CHOSEN_WEIGHT);
        assertThat(code).isEqualTo(yaml);
        assertThat(codeFull).isEqualTo(yamlFull).isEqualTo("5");
    }
}
