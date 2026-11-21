package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Authentication hardening, Phase S3 (ADR-047): the structure the silent renewal depends on, pinned as text (there is no
 * JavaScript test runner). The behaviour, including "two calls renew once", is exercised in a real browser by
 * {@code scripts/live-checks/refresh_browser.py}.
 */
class RefreshMarkupTest {

    private static String appJs;
    private static String indexHtml;

    @BeforeAll
    static void load() throws IOException {
        appJs = read("static/app.js");
        indexHtml = read("static/index.html");
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = RefreshMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static String functionBody(String signature) {
        int start = appJs.indexOf(signature);
        assertThat(start).as(signature).isGreaterThanOrEqualTo(0);
        int end = appJs.indexOf("\n}\n", start);
        assertThat(end).as("end of " + signature).isGreaterThan(start);
        return appJs.substring(start, end);
    }

    @Test
    @DisplayName("S3: renewal is single-flight: concurrent callers share one in-flight promise")
    void renewalIsSingleFlight() {
        String refresh = functionBody("function refreshSession()");

        assertThat(refresh).contains("if (refreshInFlight) return refreshInFlight;");
        assertThat(refresh).contains("refreshInFlight = (async () => {");
        assertThat(refresh).contains("refreshInFlight = null;");
        assertThat(refresh).contains("'/api/auth/refresh'");
        assertThat(appJs).contains("let refreshInFlight = null;");
    }

    @Test
    @DisplayName("S3: a late answer is discarded when the user signed out or changed meanwhile; tokens are stored on success")
    void lateAnswerIsDiscarded() {
        String refresh = functionBody("function refreshSession()");

        assertThat(refresh).contains("STATE.currentUser.refreshToken !== used");
        assertThat(refresh).contains("token: data.token, refreshToken: data.refreshToken");
        assertThat(refresh).contains("storeSession(STATE.currentUser)");
    }

    @Test
    @DisplayName("S3: a 401 is renewed and repeated once, never for /api/auth/ calls and never without a refresh token")
    void repeatsOnceAndNeverForAuthCalls() {
        String fetcher = functionBody("async function authorizedFetch(");

        assertThat(fetcher).contains("first.res.status === 401");
        assertThat(fetcher).contains("!endpoint.startsWith('/api/auth/')");
        assertThat(fetcher).contains("STATE.currentUser.refreshToken");
        assertThat(fetcher).contains("alreadyRenewed");
        assertThat(fetcher.split("await send\\(\\)", -1).length - 1).as("one first send and one repeat").isEqualTo(2);
    }

    @Test
    @DisplayName("S3: apiCall starts a write once per call, not once per attempt, and sends through authorizedFetch")
    void apiCallGuardsOncePerCall() {
        String call = functionBody("async function apiCall(");

        assertThat(call.split("beginWrite\\(\\)", -1).length - 1).isEqualTo(1);
        assertThat(call.split("pendingWrites\\+\\+", -1).length - 1).isEqualTo(1);
        assertThat(call).contains("await authorizedFetch(endpoint, options)");
        assertThat(call).contains("handleSignOut(false)");
    }

    @Test
    @DisplayName("S3: signing out calls /api/auth/logout with keepalive before the state is cleared")
    void signOutEndsTheServerSession() {
        String signOut = functionBody("function handleSignOut(");
        String logout = functionBody("function endServerSession(");

        assertThat(logout).contains("'/api/auth/logout'").contains("keepalive: true");
        assertThat(logout).contains("JSON.stringify({ refreshToken })");
        assertThat(signOut.indexOf("endServerSession(")).isGreaterThanOrEqualTo(0)
                .isLessThan(signOut.indexOf("STATE.currentUser = null;"));
    }

    @Test
    @DisplayName("S3: sign-in and registration keep the refresh token; the simulation poll uses the renewing fetch")
    void tokensAreKept() {
        String login = functionBody("async function loginUser(");

        assertThat(login).contains("refreshToken: data.refreshToken");
        assertThat(functionBody("async function updateSimulationClock(")).contains("authorizedFetch('/api/simulation/status')");
    }

    @Test
    @DisplayName("S3: the registration form stores the refresh token too")
    void registrationKeepsTheRefreshToken() {
        int register = appJs.indexOf("fetch('/api/auth/register'");
        String block = appJs.substring(register, register + 1500);

        assertThat(block).contains("refreshToken: data.refreshToken");
    }

    @Test
    @DisplayName("S3: the app never writes a token to the console and has no inline handlers; asset version is 4.8")
    void hygiene() {
        assertThat(appJs).doesNotContainPattern("console\\.\\w+\\([^)]*(\\.token|\\.refreshToken|refreshToken\\b)");
        assertThat(indexHtml).doesNotContainPattern("\\son[a-z]+\\s*=");
        assertThat(indexHtml).contains("/styles.css?v=5.5").contains("/app.js?v=5.5");
    }
}
