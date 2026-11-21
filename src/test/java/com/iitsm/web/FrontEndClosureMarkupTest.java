package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase J (ADR-039): the front-end closure. There is no JavaScript test runner in this project,
 * so the structure the behaviour depends on is pinned as text; the behaviour itself is exercised in a real browser by
 * {@code scripts/live-checks/action_buttons_browser.py}.
 */
class FrontEndClosureMarkupTest {

    private static String appJs;
    private static String indexHtml;

    @BeforeAll
    static void load() throws IOException {
        appJs = read("static/app.js");
        indexHtml = read("static/index.html");
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = FrontEndClosureMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String functionBody(String signature) {
        int start = appJs.indexOf(signature);
        assertThat(start).as(signature).isGreaterThanOrEqualTo(0);
        int end = appJs.indexOf("\nfunction ", start + 1);
        int endAsync = appJs.indexOf("\nasync function ", start + 1);
        if (end < 0 || (endAsync >= 0 && endAsync < end)) {
            end = endAsync;
        }
        return appJs.substring(start, end < 0 ? appJs.length() : end);
    }

    // ---- J1: login and registration double submit -------------------------------------------------------------------

    @Test
    @DisplayName("J1: a second login while one is in flight is ignored, whatever started it (form, demo pill, persona chip)")
    void loginIgnoresASecondSubmit() {
        String login = functionBody("async function loginUser(");
        assertThat(appJs).contains("let loginInFlight = false;");
        int guard = login.indexOf("if (loginInFlight) return;");
        int first = login.indexOf("await ");
        assertThat(guard).as("the guard is in loginUser").isGreaterThanOrEqualTo(0);
        assertThat(guard).as("before the first await").isLessThan(first);
        assertThat(login).contains("loginInFlight = true;").contains("loginInFlight = false;");
        assertThat(login).as("released in finally").containsPattern("finally \\{[\\s\\S]*loginInFlight = false;");
    }

    @Test
    @DisplayName("J1: registration has the same guard, disables its button and releases both in finally")
    void registrationIgnoresASecondSubmit() {
        assertThat(appJs).contains("let registerInFlight = false;");
        int handler = appJs.indexOf("regForm.addEventListener('submit'");
        assertThat(handler).isGreaterThan(0);
        String body = appJs.substring(handler, appJs.indexOf("// Toggle Login/Register", handler));
        assertThat(body).contains("if (registerInFlight) return;").contains("registerInFlight = true;");
        assertThat(body).contains("btn-register-submit").contains("aria-busy");
        assertThat(body).containsPattern("finally \\{[\\s\\S]*registerInFlight = false;");
        assertThat(body.indexOf("if (registerInFlight) return;")).isLessThan(body.indexOf("await "));
    }

    @Test
    @DisplayName("J1: showing the sign-in page always shows the sign-in card (not the registration card left open)")
    void signInPageShowsTheLoginCard() {
        String view = functionBody("function showAuthView(");
        assertThat(view).contains("login-card").contains("register-card");
        assertThat(view).contains("getElementById('login-card').classList.remove('hidden')");
        assertThat(view).contains("getElementById('register-card').classList.add('hidden')");
    }

    // ---- J2: refresh after 400 and 403 ------------------------------------------------------------------------------

    @Test
    @DisplayName("J2: a refused write (400 or 403) refreshes the lists like a conflict does")
    void refusedWritesRefreshTheList() {
        String refresh = functionBody("function refreshAfterFailedWrite(status)");
        assertThat(refresh).contains("status === 400").contains("status === 403").contains("status === 409")
                .contains("status === 404").contains("status >= 500");
        assertThat(refresh).contains("refreshActiveTab();");
    }

    // ---- J3: keyboard focus ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("J3: a button that had keyboard focus gets it back when it is re-enabled, or its re-rendered twin does")
    void focusReturnsToTheButton() {
        String begin = functionBody("function beginWrite()");
        assertThat(begin).contains("document.activeElement === button");
        assertThat(begin).contains("restoreFocus(");
        String restore = functionBody("function restoreFocus(");
        assertThat(restore).contains("document.activeElement").contains("document.body").contains(".focus(");
        assertThat(restore).as("a re-rendered list gives a new button with the same action and ticket")
                .contains("data-id").contains("isConnected");
    }

    // ---- J5: focus trap in dialogs ---------------------------------------------------------------------------------

    @Test
    @DisplayName("J5: Tab and Shift+Tab stay inside the open dialog, the action dialog and the ticket dialog alike")
    void dialogsTrapTheTabKey() {
        assertThat(appJs).contains("const FOCUSABLE_SELECTOR");
        String trap = functionBody("function trapTabKey(");
        assertThat(trap).contains("event.shiftKey").contains("event.preventDefault()").contains("FOCUSABLE_SELECTOR");
        assertThat(appJs).containsPattern("document.addEventListener\\('keydown',[\\s\\S]*?event.key !== 'Tab'[\\s\\S]*?trapTabKey\\(");
        String openDialog = functionBody("function openDialogElement()");
        assertThat(openDialog).contains("admin-ticket-detail-modal").contains("modal-container").contains("classList.contains('hidden')");
    }

    @Test
    @DisplayName("J5: the action dialog moves focus into itself when it opens and gives it back to the opener when it closes")
    void actionDialogManagesFocus() {
        String open = functionBody("function openModal(");
        assertThat(open).contains("modalOpener = document.activeElement").contains(".focus(");
        String close = functionBody("function closeModal(");
        assertThat(close).contains("modalOpener").contains(".focus(").contains("restoreFocus(opener)");
    }

    // ---- J4 (found by the button run): employee buttons follow allowedActions ---------------------------------------

    @Test
    @DisplayName("J4: the employee's Provide info, Verify and Reject buttons follow allowedActions, not the internal status")
    void employeeButtonsFollowAllowedActions() {
        // Since ADR-050 the buttons live on the featured ticket card of the employee portal
        String cards = functionBody("function empFeaturedActionsHtml(");
        assertThat(cards).contains("awaitsMyReply(t)").contains("includes('verify')").contains("includes('reject')");
        assertThat(cards).as("a public status can never be PENDING_INFO").doesNotContain("PENDING_INFO");
        String stats = functionBody("function updateEmployeeStats(");
        assertThat(stats).contains("awaitsMyReply").doesNotContain("PENDING_INFO");
        assertThat(functionBody("function awaitsMyReply(")).contains("allowedActions").contains("provideInfo");
    }

    // ---- J6: numbers and names -------------------------------------------------------------------------------------

    @Test
    @DisplayName("J6: the composite score shows three decimals like the weighted contributions beside it")
    void compositeScoreShowsThreeDecimals() {
        assertThat(appJs).contains("tdNum(decision.compositeScore, 3)");
        assertThat(appJs).doesNotContain("tdNum(decision.compositeScore, 2)");
        assertThat(appJs).contains("tdNum(factor.weightedScore, 3)");
    }

    @Test
    @DisplayName("J6: the triage card shows the staff ticket name next to the id")
    void triageCardShowsTheDisplayName() {
        String card = functionBody("function triageCardHtml(");
        assertThat(card).contains("ticketNameHtml(").contains("item.displayId");
    }

    @Test
    @DisplayName("the static files carry the Phase J asset version and no inline handlers")
    void assetVersionAndCsp() {
        assertThat(indexHtml).contains("/styles.css?v=5.5").contains("/app.js?v=5.5");
        assertThat(indexHtml).doesNotContainPattern("\\son(click|submit|change|load)\\s*=");
        assertThat(indexHtml).contains("id=\"btn-register-submit\"").contains("id=\"btn-login-submit\"");
    }
}
