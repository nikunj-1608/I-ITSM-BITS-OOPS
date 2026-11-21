package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Authentication hardening, Phase S4 (ADR-048): the structure of the second sign-in step and the Security dialog, pinned as
 * text (there is no JavaScript test runner). The behaviour is exercised in a real browser by
 * {@code scripts/live-checks/mfa_browser.py}.
 */
class MfaMarkupTest {

    private static String appJs;
    private static String indexHtml;

    @BeforeAll
    static void load() throws IOException {
        appJs = read("static/app.js");
        indexHtml = read("static/index.html");
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = MfaMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
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
    @DisplayName("S4: the sign-in card has a hidden second step with a code input, submit and back buttons, and an alert")
    void secondStepMarkup() {
        int form = indexHtml.indexOf("id=\"form-mfa\"");

        assertThat(form).isGreaterThan(indexHtml.indexOf("id=\"login-card\""));
        assertThat(indexHtml.substring(form, form + 200)).contains("hidden");
        for (String id : new String[] {"mfa-code", "btn-mfa-submit", "btn-mfa-cancel", "mfa-error-alert"}) {
            assertThat(indexHtml).as(id).contains("id=\"" + id + "\"");
        }
        assertThat(indexHtml).contains("autocomplete=\"one-time-code\"");
    }

    @Test
    @DisplayName("S4: the header has a Security button, hidden until an administrator signs in")
    void securityButton() {
        int button = indexHtml.indexOf("id=\"btn-security\"");

        assertThat(button).isGreaterThan(indexHtml.indexOf("id=\"app-view\""));
        assertThat(button).isLessThan(indexHtml.indexOf("id=\"btn-logout\""));
        assertThat(indexHtml.substring(button, button + 160)).contains("hidden");
        assertThat(functionBody("function setAuthenticatedUser(")).contains("user.role !== 'ROLE_ADMIN'");
        assertThat(functionBody("function handleSignOut(")).contains("btn-security").contains("hideMfaStep()");
        assertThat(functionBody("function handleSignOut(")).as("the dialog can show a secret, so it ends with the session")
                .contains("getElementById('security-modal')) closeModal()");
    }

    @Test
    @DisplayName("S4: the password step branches on mfaRequired and shows the second step instead of signing in")
    void loginBranches() {
        String login = functionBody("async function loginUser(");

        assertThat(login).contains("data.mfaRequired").contains("showMfaStep(data.mfaToken)");
        assertThat(login.indexOf("data.mfaRequired")).isLessThan(login.indexOf("setAuthenticatedUser("));
    }

    @Test
    @DisplayName("S4: submitting the code is guarded against a second submit, sends the challenge, keeps the refresh token")
    void submitMfaCode() {
        String submit = functionBody("async function submitMfaCode(");

        assertThat(submit).contains("if (mfaSubmitInFlight || !mfaChallengeToken) return;");
        assertThat(submit).contains("'/api/auth/mfa/verify'").contains("mfaToken: mfaChallengeToken");
        assertThat(submit).contains("refreshToken: data.refreshToken").contains("res.status === 429");
        assertThat(submit).contains("mfaSubmitInFlight = false");
    }

    @Test
    @DisplayName("S4: the Security dialog is drawn through openModal (so the Tab trap and focus return apply), with id security-modal")
    void dialogUsesTheSharedDialog() {
        assertThat(functionBody("function secRender(")).contains("openModal(`").contains("id=\"security-modal\"");
        assertThat(functionBody("async function openSecurityModal(")).contains("openModal(").contains("securityDialogSeq");
        assertThat(appJs).doesNotContain("getElementById('security-modal').classList");
    }

    @Test
    @DisplayName("S4: the dialog covers status, setup with secret and otpauth link as text, confirm, recovery codes, disable, sign out everywhere")
    void dialogContent() {
        String render = functionBody("function secRender(");

        assertThat(render).contains("Two-step verification is <strong>off</strong>").contains("Two-step verification is on");
        assertThat(render).contains("id=\"sec-secret\"").contains("id=\"sec-uri\"");
        assertThat(render).contains("id=\"form-sec-enable\"").contains("id=\"sec-recovery-codes\"");
        assertThat(render).contains("id=\"form-sec-disable\"").contains("id=\"sec-disable-password\"").contains("id=\"sec-disable-code\"");
        assertThat(render).contains("id=\"btn-sec-logout-all\"");
        assertThat(render).doesNotContain("<img").doesNotContain("qr");
        assertThat(functionBody("async function secLogoutAll(")).contains("'/api/auth/logout-all'").contains("handleSignOut(false)");
    }

    @Test
    @DisplayName("S4: every dynamic value in the dialog goes through escapeHtml")
    void escaping() {
        String render = functionBody("function secRender(");

        assertThat(render).contains("${escapeHtml(extra.secret)}").contains("${escapeHtml(extra.uri)}").contains("escapeHtml(code)");
        assertThat(render.replaceAll("\\$\\{(escapeHtml\\([^}]*\\)|items|body)}", "")).doesNotContain("${extra");
    }

    @Test
    @DisplayName("S4: a wrong code never signs the user out: the dialog tells wrong codes (code) from an ended session (errorCode) and renews once")
    void wrongCodeIsNotASignOut() {
        String call = functionBody("async function secCall(");

        assertThat(call).contains("payload.errorCode === 'ERR_UNAUTHORIZED'").contains("await refreshSession()");
        assertThat(call).doesNotContain("handleSignOut");
        assertThat(appJs).contains("ERR_INVALID_MFA_CODE").contains("ERR_INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("S4: the secret, codes and password are not written to the console or to storage; no inline handlers; asset version 4.8")
    void hygiene() {
        String section = appJs.substring(appJs.indexOf("// 18. Security"));

        assertThat(section).doesNotContainPattern("console\\.");
        assertThat(section).doesNotContain("sessionStorage").doesNotContain("localStorage");
        assertThat(indexHtml).doesNotContainPattern("\\son[a-z]+\\s*=");
        assertThat(indexHtml).contains("/styles.css?v=5.2").contains("/app.js?v=5.2");
    }
}
