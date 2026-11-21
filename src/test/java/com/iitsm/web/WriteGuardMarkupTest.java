package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the contract of the double-submit protection and conflict handling in {@code app.js} (ADR-017). There is no
 * JavaScript test runner in this project, so the structure the behaviour depends on is pinned as text; the
 * behaviour itself was verified in a real browser (see docs/concurrent-conflicts-fix-summary.md).
 */
class WriteGuardMarkupTest {

    private static String appJs;
    private static String indexHtml;
    private static String apiCall;

    @BeforeAll
    static void load() throws IOException {
        appJs = read("static/app.js");
        indexHtml = read("static/index.html");
        int start = appJs.indexOf("async function apiCall(");
        assertThat(start).as("apiCall exists").isGreaterThan(0);
        apiCall = appJs.substring(start, appJs.indexOf("// ====", start));
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = WriteGuardMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("The clicked button is tracked in the capture phase, before any handler can start a write")
    void clickedButtonIsTrackedBeforeHandlers() {
        assertThat(appJs).contains("document.addEventListener('click'");
        assertThat(appJs).containsPattern("lastClickedButton = button;[\\s\\S]*?\\}, true\\);");
    }

    @Test
    @DisplayName("apiCall disables the button synchronously, before its first await, and always releases it")
    void writesDisableTheButtonBeforeAwaiting() {
        int guard = apiCall.indexOf("beginWrite()");
        int firstAwait = apiCall.indexOf("await ");
        assertThat(guard).as("beginWrite() is called in apiCall").isGreaterThan(0);
        assertThat(guard).as("before the first await").isLessThan(firstAwait);
        assertThat(apiCall).contains("finally {").contains("releaseWrite();");
        assertThat(appJs).contains("button.disabled = true;").contains("aria-busy");
        assertThat(appJs).as("cool-down covers a human double-click").contains("const WRITE_COOLDOWN_MS = 600;");
        assertThat(appJs).containsPattern("setTimeout\\(\\(\\) => \\{[\\s\\S]*?button.disabled = false;[\\s\\S]*?\\}, WRITE_COOLDOWN_MS\\);");
    }

    @Test
    @DisplayName("Only writes are guarded; reads (GET) are never blocked")
    void readsAreNotGuarded() {
        assertThat(apiCall).contains("!== 'GET'").contains("isWrite ? beginWrite()");
    }

    @Test
    @DisplayName("A failed request carries status and code, and conflicts get a friendly message and a refresh")
    void conflictsAreFriendlyAndRefreshTheList() {
        assertThat(apiCall).contains("failure.status = res.status;").contains("failure.code = body.code;");
        assertThat(apiCall).contains("refreshAfterFailedWrite(res.status)");
        assertThat(appJs).contains("const CONFLICT_CODE = 'ERR_CONCURRENT_MODIFICATION';");
        assertThat(appJs).contains("const STALE_STATE_CODE = 'ERR_INVALID_STATE_TRANSITION';");
        assertThat(appJs).contains("The list has been refreshed");
        assertThat(appJs).containsPattern("function refreshAfterFailedWrite\\(status\\)[\\s\\S]*?refreshActiveTab\\(\\);");
        assertThat(appJs).contains("status === 400 || status === 403 || status === 409 || status === 404 || status >= 500");
    }

    @Test
    @DisplayName("Assets are cache-busted to 3.5 and no inline handlers were introduced")
    void assetsAreCacheBustedAndCspSafe() {
        assertThat(indexHtml).contains("/styles.css?v=5.4").contains("/app.js?v=5.4");
        assertThat(indexHtml).doesNotContainPattern("\\son(click|submit|change|load)\\s*=");
    }
}
