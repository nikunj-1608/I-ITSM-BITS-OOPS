package com.iitsm.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Authentication hardening, Phase S3 (ADR-047): the shipped {@code application.yml} gives every profile an access token
 * of at most 15 minutes (the web app renews it silently, so no profile needs a longer one) and positive refresh settings.
 * The files are read directly; nothing here starts an application.
 */
class RefreshConfigContractTest {

    private static final String MAIN_YML = "src/main/resources/application.yml";
    private static final String TEST_YML = "src/test/resources/application.yml";
    private static final String JWT_TTL = "iitsm.security.jwt.expiration-ms";
    private static final String REFRESH = "iitsm.security.refresh.";
    private static final long FIFTEEN_MINUTES_MS = 15 * 60 * 1000;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[A-Z_]+:(.*)}");

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

    private static String defaultOf(Object value) {
        String text = String.valueOf(value);
        Matcher placeholder = PLACEHOLDER.matcher(text);
        return placeholder.matches() ? placeholder.group(1) : text;
    }

    /** The profile's own value when it has one, else the base value, with an unset environment variable resolved. */
    private static String effective(String profile, String property) throws IOException {
        Object own = profile(profile).map(source -> source.getProperty(property)).orElse(null);
        return defaultOf(own != null ? own : base().getProperty(property));
    }

    @Test
    @DisplayName("S3: every profile of the shipped yml has an access token lifetime of at most 15 minutes")
    void accessTokenIsShortInEveryProfile() throws IOException {
        for (String profile : List.of("dev", "prod", "demo")) {
            assertThat(Long.parseLong(effective(profile, JWT_TTL))).as(profile).isPositive().isLessThanOrEqualTo(FIFTEEN_MINUTES_MS);
        }
        assertThat(Long.parseLong(defaultOf(base().getProperty(JWT_TTL)))).isPositive().isLessThanOrEqualTo(FIFTEEN_MINUTES_MS);
    }

    @Test
    @DisplayName("S3: no profile block overrides the lifetime with a longer one (the 120-minute overrides are gone)")
    void noProfileOverrideIsLonger() throws IOException {
        for (String profile : List.of("dev", "prod", "demo")) {
            Object own = profile(profile).map(source -> source.getProperty(JWT_TTL)).orElse(null);
            if (own != null) {
                assertThat(Long.parseLong(defaultOf(own))).as(profile).isLessThanOrEqualTo(FIFTEEN_MINUTES_MS);
            }
        }
    }

    @Test
    @DisplayName("S3: the test profile's lifetime is at most 15 minutes too")
    void testProfileLifetime() throws IOException {
        Object ttl = documents(TEST_YML).get(0).getProperty(JWT_TTL);

        assertThat(Long.parseLong(String.valueOf(ttl))).isPositive().isLessThanOrEqualTo(FIFTEEN_MINUTES_MS);
    }

    @Test
    @DisplayName("S3: the refresh settings are present and positive: 8 hours, 10 sessions, hourly cleanup, 7 days")
    void refreshSettings() throws IOException {
        assertThat(defaultOf(base().getProperty(REFRESH + "ttl-hours"))).isEqualTo("8");
        assertThat(defaultOf(base().getProperty(REFRESH + "max-sessions-per-user"))).isEqualTo("10");
        assertThat(Long.parseLong(defaultOf(base().getProperty(REFRESH + "cleanup-interval-ms")))).isEqualTo(3_600_000L);
        assertThat(Long.parseLong(defaultOf(base().getProperty(REFRESH + "purge-after-days")))).isPositive();
        PropertySource<?> test = documents(TEST_YML).get(0);
        assertThat(Long.parseLong(String.valueOf(test.getProperty(REFRESH + "cleanup-interval-ms")))).isEqualTo(3_600_000L);
    }
}
