package com.iitsm.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.infrastructure.security.RateLimitRules;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Authentication hardening, Phase S2 (ADR-046): what the shipped {@code application.yml} says about rate limiting
 * (on by default in prod, off in every other profile and in the test profile, every number positive) and that a bad
 * value stops the start-up. The files are read directly; nothing here starts an application.
 */
class RateLimitConfigContractTest {

    private static final String MAIN_YML = "src/main/resources/application.yml";
    private static final String TEST_YML = "src/test/resources/application.yml";
    private static final String KEY = "iitsm.security.rate-limit.";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[A-Z_]+:(.*)}");
    private static final List<String> NUMBERS = List.of("max-tracked-clients",
            "login-capacity", "login-window-seconds", "register-capacity", "register-window-seconds",
            "refresh-capacity", "refresh-window-seconds", "mfa-capacity", "mfa-window-seconds");

    private static List<PropertySource<?>> documents(String file) throws IOException {
        return new YamlPropertySourceLoader().load(file, new FileSystemResource(file));
    }

    private static Optional<PropertySource<?>> profile(String name) throws IOException {
        return documents(MAIN_YML).stream()
                .filter(source -> name.equals(source.getProperty("spring.config.activate.on-profile")))
                .findFirst();
    }

    private static PropertySource<?> base() throws IOException {
        return documents(MAIN_YML).stream()
                .filter(source -> source.getProperty("spring.config.activate.on-profile") == null)
                .findFirst().orElseThrow();
    }

    /** The value a property has when its environment variable is not set. */
    private static String defaultOf(Object value) {
        String text = String.valueOf(value);
        Matcher placeholder = PLACEHOLDER.matcher(text);
        return placeholder.matches() ? placeholder.group(1) : text;
    }

    private static String effective(String profile, String property) throws IOException {
        Object own = profile(profile).orElseThrow().getProperty(KEY + property);
        return defaultOf(own != null ? own : base().getProperty(KEY + property));
    }

    @Test
    @DisplayName("S2: rate limiting is on by default in prod")
    void prodEnabled() throws IOException {
        assertThat(effective("prod", "enabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("S2: off by default in the base, dev and demo profiles, and in the test profile")
    void othersDisabled() throws IOException {
        assertThat(defaultOf(base().getProperty(KEY + "enabled"))).isEqualTo("false");
        assertThat(effective("dev", "enabled")).isEqualTo("false");
        assertThat(effective("demo", "enabled")).isEqualTo("false");
        Object test = documents(TEST_YML).get(0).getProperty(KEY + "enabled");
        assertThat(String.valueOf(test)).isEqualTo("false");
    }

    @Test
    @DisplayName("S2: every capacity, window and the key limit is positive; X-Forwarded-For is not trusted by default")
    void numbersArePositive() throws IOException {
        for (String property : NUMBERS) {
            assertThat(Long.parseLong(defaultOf(base().getProperty(KEY + property)))).as(property).isPositive();
        }
        assertThat(defaultOf(base().getProperty(KEY + "trusted-proxy-hops"))).isEqualTo("0");
    }

    @Test
    @DisplayName("S2: a non-positive capacity or window, or negative proxy hops, stops the start-up naming the property")
    void badValuesFailFast() {
        assertThatThrownBy(() -> new RateLimitConfig(true, 0, 100, 0, 60, 5, 600, 60, 60, 10, 60))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(KEY + "login-capacity");
        assertThatThrownBy(() -> new RateLimitConfig(true, 0, 100, 10, 60, 5, 0, 60, 60, 10, 60))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(KEY + "register-window-seconds");
        assertThatThrownBy(() -> new RateLimitConfig(true, -1, 100, 10, 60, 5, 600, 60, 60, 10, 60))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(KEY + "trusted-proxy-hops");
    }

    @Test
    @DisplayName("S2: the four sign-in routes are configured, each with its own limiter")
    void fourRoutes() {
        RateLimitRules rules = new RateLimitConfig(true, 0, 100, 10, 60, 5, 600, 60, 60, 10, 60).rateLimitRules();

        assertThat(rules.routesByPath()).containsOnlyKeys("/api/auth/login", "/api/auth/register",
                "/api/auth/refresh", "/api/auth/mfa/verify");
        assertThat(rules.routesByPath().values().stream().map(RateLimitRules.Route::limiter).distinct()).hasSize(4);
    }
}
