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
 * Remaining-defects plan, Phase E (ADR-033, stress-audit item L4, decision D12): what each profile of the shipped
 * {@code application.yml} says about exposure. The test profile has an {@code application.yml} of its own that hides
 * this one on the class path, so the file is read directly. Nothing here starts an application.
 */
class ExposureConfigContractTest {

    private static final String MAIN_YML = "src/main/resources/application.yml";

    private static List<PropertySource<?>> documents() throws IOException {
        return new YamlPropertySourceLoader().load("main", new FileSystemResource(MAIN_YML));
    }

    private static Optional<PropertySource<?>> profile(String name) throws IOException {
        return documents().stream()
                .filter(source -> name.equals(source.getProperty("spring.config.activate.on-profile")))
                .findFirst();
    }

    private static PropertySource<?> base() throws IOException {
        return documents().stream()
                .filter(source -> source.getProperty("spring.config.activate.on-profile") == null)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("L4: the prod profile switches the API documentation off")
    void prodSwitchesSwaggerOff() throws IOException {
        PropertySource<?> prod = profile("prod").orElseThrow();

        assertThat(prod.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
        assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("L4: dev and demo keep the API documentation (it is part of the demo)")
    void devAndDemoKeepSwagger() throws IOException {
        for (String name : new String[] {"dev", "demo"}) {
            Optional<PropertySource<?>> document = profile(name);
            document.ifPresent(source -> assertThat(source.getProperty("springdoc.api-docs.enabled")).isNull());
        }
        assertThat(base().getProperty("springdoc.api-docs.enabled")).isNull();
    }

    @Test
    @DisplayName("D12: prod and demo limit registration to the company domain, any environment variable can change it")
    void prodAndDemoLimitRegistration() throws IOException {
        for (String name : new String[] {"prod", "demo"}) {
            Object domains = profile(name).orElseThrow().getProperty("iitsm.security.allowed-email-domains");
            assertThat(domains).as(name).isEqualTo("${ALLOWED_EMAIL_DOMAINS:nimbusfinserve.com}");
        }
    }

    @Test
    @DisplayName("D12: by default (dev, test) registration accepts any domain")
    void defaultAcceptsAnyDomain() throws IOException {
        assertThat(base().getProperty("iitsm.security.allowed-email-domains")).isEqualTo("${ALLOWED_EMAIL_DOMAINS:}");
    }

    @Test
    @DisplayName("D11: the lock duration is configurable and defaults to 15 minutes")
    void lockoutIsConfigurable() throws IOException {
        assertThat(base().getProperty("iitsm.security.lockout-minutes")).isEqualTo("${LOCKOUT_MINUTES:15}");
    }
}
