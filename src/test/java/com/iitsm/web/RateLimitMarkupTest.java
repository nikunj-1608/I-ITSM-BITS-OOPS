package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Authentication hardening, Phase S2 (ADR-046): the sign-in and registration forms tell the user how long to wait
 * after HTTP 429, from the {@code Retry-After} header. Pinned as text (no JavaScript test runner); the behaviour is
 * exercised in a real run by {@code scripts/live-checks/rate_limit_check.py}.
 */
class RateLimitMarkupTest {

    private static String appJs;
    private static String indexHtml;

    @BeforeAll
    static void load() throws IOException {
        appJs = read("static/app.js");
        indexHtml = read("static/index.html");
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = RateLimitMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String between(String from, String to) {
        int start = appJs.indexOf(from);
        assertThat(start).as(from).isGreaterThanOrEqualTo(0);
        int end = appJs.indexOf(to, start);
        assertThat(end).as(to).isGreaterThan(start);
        return appJs.substring(start, end);
    }

    @Test
    @DisplayName("S2: one helper reads Retry-After and words the wait")
    void helperReadsRetryAfter() {
        String helper = between("function rateLimitMessage(res)", "\n}");

        assertThat(helper).contains("res.headers.get('Retry-After')");
        assertThat(helper).contains("Too many attempts from this network. Try again in ");
    }

    @Test
    @DisplayName("S2: the login handler answers 429 with the helper, before reading the body")
    void loginUsesHelper() {
        String login = between("async function loginUser(", "const data = await res.json();");

        assertThat(login).contains("res.status === 429");
        assertThat(login).contains("rateLimitMessage(res)");
        assertThat(login).doesNotContain("Account temporarily locked");
    }

    @Test
    @DisplayName("S2: the registration handler answers 429 with the helper")
    void registerUsesHelper() {
        String register = between("fetch('/api/auth/register'", "const data = await res.json();");

        assertThat(register).contains("res.status === 429");
        assertThat(register).contains("rateLimitMessage(res)");
    }

    @Test
    @DisplayName("S2: the asset version is bumped for both files (4.8 since S4)")
    void assetVersionBumped() {
        assertThat(indexHtml).contains("/styles.css?v=5.2").contains("/app.js?v=5.2");
    }
}
