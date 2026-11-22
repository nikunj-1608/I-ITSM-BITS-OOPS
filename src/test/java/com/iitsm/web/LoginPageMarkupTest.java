package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Login page redesign (front end only): the sign-in card shows the Swiftdesk logo, an e-mail and a password field with
 * a show/hide button, a display-only "Remember me" box and the Sign In button. There is no role dropdown, no
 * "Forgot password?" link and no Self-Register button on the card. There is no JavaScript test runner, so the structure
 * is pinned as text; the behaviour is exercised in Microsoft Edge by {@code scripts/live-checks/login_page_browser.py}.
 */
class LoginPageMarkupTest {

    private static final String CSS_START = "/* Login page (login-) */";
    private static final String CSS_END = "/* End Login page */";

    private static String indexHtml;
    private static String appJs;
    private static String stylesCss;
    private static String card;
    private static String css;

    @BeforeAll
    static void load() throws IOException {
        indexHtml = read("static/index.html");
        appJs = read("static/app.js");
        stylesCss = read("static/styles.css");
        int start = indexHtml.indexOf("<div id=\"login-card\"");
        int end = indexHtml.indexOf("<!-- Self-Registration Card");
        assertThat(start).as("sign-in card").isGreaterThan(0);
        assertThat(end).as("end of the sign-in card's own markup").isGreaterThan(start);
        card = indexHtml.substring(start, end);
        int cssStart = stylesCss.indexOf(CSS_START);
        int cssEnd = stylesCss.indexOf(CSS_END);
        assertThat(cssStart).as("login block of styles.css").isGreaterThanOrEqualTo(0);
        assertThat(cssEnd).as("end marker of the login block").isGreaterThan(cssStart);
        css = stylesCss.substring(cssStart, cssEnd);
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = LoginPageMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static String functionBody(String signature) {
        int start = appJs.indexOf(signature);
        assertThat(start).as(signature).isGreaterThanOrEqualTo(0);
        int end = appJs.length();
        for (String next : new String[] {"\nfunction ", "\nasync function ", "\n// ====="}) {
            int found = appJs.indexOf(next, start + signature.length());
            if (found > 0 && found < end) {
                end = found;
            }
        }
        return appJs.substring(start, end);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What the card shows
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the card shows the Swiftdesk logo, and the file it points at ships with the app")
    void logoIsShown() throws IOException {
        assertThat(card).contains("<img src=\"/assets/swiftdesk-logo.png\" alt=\"Swiftdesk\" class=\"login-logo\"");
        try (InputStream stream = LoginPageMarkupTest.class.getClassLoader()
                .getResourceAsStream("static/assets/swiftdesk-logo.png")) {
            assertThat(stream).as("static/assets/swiftdesk-logo.png").isNotNull();
        }
        assertThat(card).as("the old icon header is gone").doesNotContain("brand-logo").doesNotContain("<h2");
    }

    @Test
    @DisplayName("there is no role dropdown, no forgot-password link and no Self-Register button on the card")
    void removedThingsStayRemoved() {
        assertThat(card).doesNotContain("<select");
        assertThat(card.toLowerCase()).doesNotContain("choose your role").doesNotContain("forgot")
                .doesNotContain("btn-toggle-register").doesNotContain("self-register").doesNotContain("new employee");
    }

    @Test
    @DisplayName("the e-mail and password fields keep their ids, types and autocomplete hints")
    void fields() {
        assertThat(card).contains("<label class=\"form-label login-label\" for=\"login-email\">EMAIL ADDRESS</label>");
        assertThat(card).contains("<label class=\"form-label login-label\" for=\"login-password\">PASSWORD</label>");
        assertThat(card).contains("type=\"email\" id=\"login-email\"").contains("autocomplete=\"username\"")
                .contains("placeholder=\"name@company.com\"");
        assertThat(card).contains("type=\"password\" id=\"login-password\"").contains("autocomplete=\"current-password\"");
        assertThat(card).contains("id=\"form-login\"").contains("id=\"btn-login-submit\"").contains("type=\"submit\"");
    }

    @Test
    @DisplayName("Sign In has an arrow, and the error alert and the two-step form are still on the card")
    void signInAndTheRestOfTheCard() {
        int button = card.indexOf("id=\"btn-login-submit\"");
        String submit = card.substring(button, card.indexOf("</button>", button));
        assertThat(submit).contains("<span>Sign In</span>").contains("<svg");
        assertThat(card).contains("id=\"login-error-alert\"").contains("id=\"form-mfa\"").contains("id=\"mfa-code\"")
                .contains("id=\"btn-mfa-cancel\"");
        assertThat(indexHtml).contains("id=\"form-register\"");
    }

    @Test
    @DisplayName("the demo accounts list is gone from the page, and the script no longer assumes it exists")
    void demoAccountsAreGone() {
        assertThat(indexHtml).doesNotContain("demo-accounts-container").doesNotContain("demo-pill")
                .doesNotContain("Corporate Structure").doesNotContain("Simulated Org");
        assertThat(appJs).doesNotContain("querySelectorAll('.demo-pill')");
        assertThat(appJs).doesNotContain("getElementById('demo-accounts-container').classList");
        assertThat(functionBody("async function loadPublicConfig("))
                .contains("const demoAccounts = document.getElementById('demo-accounts-container');")
                .contains("if (demoAccounts) demoAccounts.classList.toggle('hidden', !STATE.demoMode);")
                .contains("persona-switcher-bar");
    }

    @Test
    @DisplayName("Remember me is visible and display only: the script never reads it")
    void rememberMeIsDisplayOnly() {
        assertThat(card).contains("<input type=\"checkbox\" id=\"login-remember\"")
                .contains("<span>Remember me for 30 days</span>");
        assertThat(appJs).doesNotContain("login-remember");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The show / hide password button
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the eye is a real button that says what it does")
    void eyeButtonMarkup() {
        assertThat(card).contains("<button type=\"button\" id=\"btn-login-toggle-password\" class=\"login-eye\""
                + " aria-label=\"Show password\" aria-pressed=\"false\">");
    }

    @Test
    @DisplayName("the script toggles the field type with the state it reports, and hides the password on sign-out")
    void eyeButtonBehaviour() {
        String set = functionBody("function setPasswordVisible(");
        assertThat(set).contains("input.type = visible ? 'text' : 'password'")
                .contains("'aria-pressed'").contains("'Hide password'").contains("'Show password'");
        assertThat(functionBody("function showAuthView(")).contains("setPasswordVisible(false)");
        assertThat(functionBody("function showMfaStep(")).contains("setPasswordVisible(false)");
        assertThat(appJs).contains("pwdToggle.addEventListener('click', () => setPasswordVisible(pwdInput.type === 'password'))");
    }

    @Test
    @DisplayName("the register toggle no longer assumes its button exists")
    void registerToggleIsNullSafe() {
        assertThat(appJs).doesNotContain("document.getElementById('btn-toggle-register').addEventListener");
        assertThat(appJs).contains("const registerToggle = document.getElementById('btn-toggle-register');")
                .contains("if (registerToggle) registerToggle.addEventListener('click'");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Styles and page rules
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("every login- class used on the card has a rule in the login block")
    void everyLoginClassIsStyled() {
        Set<String> used = new HashSet<>();
        Matcher m = Pattern.compile("login-[a-z-]+").matcher(card.replaceAll("id=\"[^\"]*\"", "").replaceAll("for=\"[^\"]*\"", ""));
        while (m.find()) {
            used.add(m.group());
        }
        used.remove("login-card");
        assertThat(used).contains("login-logo", "login-label", "login-field", "login-field-icon", "login-input",
                "login-eye", "login-remember", "login-remember-box", "login-submit");
        for (String name : used) {
            assertThat(css).as("a rule for ." + name).contains("." + name);
        }
    }

    @Test
    @DisplayName("every rule of the login block is scoped to the sign-in card, so no other screen changes")
    void rulesAreScoped() {
        String stripped = css.replaceAll("(?s)/\\*.*?\\*/", "");
        int depth = 0;
        StringBuilder selector = new StringBuilder();
        for (char c : stripped.toCharArray()) {
            if (c == '{') {
                String text = selector.toString().trim();
                if (depth == 0 && !text.startsWith("@media")) {
                    for (String part : text.split(",")) {
                        assertThat(part.trim()).as("selector scope").matches("(html\\[data-theme=\"dark\"\\] )?#login-card.*");
                    }
                }
                if (depth == 1 && !text.isEmpty()) {
                    for (String part : text.split(",")) {
                        assertThat(part.trim()).as("selector scope in @media").startsWith("#login-card");
                    }
                }
                depth++;
                selector.setLength(0);
            } else if (c == '}') {
                depth--;
                selector.setLength(0);
            } else if (depth <= 1) {
                selector.append(c);
            }
        }
        assertThat(depth).isZero();
    }

    @Test
    @DisplayName("the dark theme has its own login colours, so the fields and the logo stay readable")
    void darkThemeIsHandled() {
        assertThat(css).contains("html[data-theme=\"dark\"] #login-card {").contains("--login-text:")
                .contains("html[data-theme=\"dark\"] #login-card .login-logo");
    }

    @Test
    @DisplayName("no inline handlers, no external URLs, and the asset version is current")
    void pageRules() {
        assertThat(card).doesNotContainPattern("\\son[a-z]+=");
        assertThat(card).doesNotContain("http://").doesNotContain("https://");
        assertThat(css).doesNotContain("http://").doesNotContain("https://").doesNotContain("@import");
        assertThat(indexHtml).contains("/styles.css?v=5.6").contains("/app.js?v=5.6");
    }
}
