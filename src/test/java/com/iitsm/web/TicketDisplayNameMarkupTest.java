package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the web side of the staff ticket name (priority + id, e.g. P2-FIN-2026-000142): it is shown next to
 * the plain id in the staff screens, never on the employee screens, and the static files stay cache-busted,
 * CSP-safe (no inline handlers) and offline (no external resources).
 */
class TicketDisplayNameMarkupTest {

    private static String indexHtml;
    private static String stylesCss;
    private static String appJs;

    @BeforeAll
    static void loadStaticResources() throws IOException {
        indexHtml = read("static/index.html");
        stylesCss = read("static/styles.css");
        appJs = read("static/app.js");
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = TicketDisplayNameMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Source text of one top-level function, up to the next top-level declaration. */
    private static String functionBody(String name) {
        int start = appJs.indexOf("function " + name + "(");
        assertThat(start).as("function " + name).isGreaterThanOrEqualTo(0);
        int end = appJs.indexOf("\nfunction ", start + 1);
        int endAsync = appJs.indexOf("\nasync function ", start + 1);
        if (end < 0 || (endAsync >= 0 && endAsync < end)) {
            end = endAsync;
        }
        return appJs.substring(start, end < 0 ? appJs.length() : end);
    }

    @Test
    @DisplayName("One helper builds the name, escapes it and hides it when it would only repeat the id")
    void helperEscapesAndHidesRedundantName() {
        String helper = functionBody("ticketNameHtml");
        assertThat(helper).contains("ticket.displayId", "escapeHtml(ticket.displayId)", "ticket.displayId === ticket.id",
                "ticket-display-name-block");
    }

    @Test
    @DisplayName("Technician cards, team lead lists and the Command Centre table show the name next to the id")
    void staffScreensShowTheNameAlongsideTheId() {
        assertThat(functionBody("renderTechnicianQueue")).contains("ticketNameHtml(t)");
        assertThat(appJs).contains("${escapeHtml(t.id)}</span>${ticketNameHtml(t, true)}</td>");
        assertThat(appJs.split("ticketNameHtml\\(t\\)", -1).length - 1)
                .as("technician card + two team lead lists").isGreaterThanOrEqualTo(3);
        assertThat(functionBody("renderTicketDetailHeader")).contains("ticket-detail-display-name", "d.displayId");
    }

    @Test
    @DisplayName("The plain id stays the clickable link and the key used by every button")
    void plainIdRemainsTheKey() {
        assertThat(appJs).contains("btn-admin-view-ticket\" role=\"button\" tabindex=\"0\" title=\"View full ticket "
                + "details\" data-id=\"${escapeHtml(t.id)}\"");
        assertThat(appJs).doesNotContain("data-id=\"${t.displayId}\"").doesNotContain("data-id=\"${escapeHtml(t.displayId)}\"");
    }

    @Test
    @DisplayName("Employee screens never use the staff ticket name")
    void employeeScreensDoNotUseIt() {
        assertThat(functionBody("renderEmployeeTickets")).doesNotContain("displayId").doesNotContain("ticketNameHtml");
        assertThat(functionBody("updateEmployeeStats")).doesNotContain("displayId");
        assertThat(appJs.split("displayId", -1).length - 1).as("uses of displayId in the whole script")
                .isLessThanOrEqualTo(8);
    }

    @Test
    @DisplayName("The detail dialog declares the name element and the stylesheet defines its classes")
    void markupAndStylesExist() {
        assertThat(indexHtml).contains("id=\"ticket-detail-display-name\"");
        assertThat(stylesCss).contains(".ticket-display-name {", ".ticket-display-name-block {",
                ".ticket-display-name:empty");
    }

    @Test
    @DisplayName("The search box tells staff they can type the ticket name")
    void searchHintMentionsTicketName() {
        assertThat(indexHtml).contains("placeholder=\"Search title, ID or ticket name...\"");
    }

    @Test
    @DisplayName("Assets are cache-busted to 3.4 and remain CSP-safe and offline")
    void assetsAreCacheBustedAndSafe() {
        assertThat(indexHtml).contains("/styles.css?v=3.4").contains("/app.js?v=3.4");
        assertThat(indexHtml + appJs).doesNotContainPattern("\\son(click|change|submit|load|keydown|input)\\s*=");
        assertThat(indexHtml + stylesCss).doesNotContainPattern("https?://").doesNotContain("@import");
    }
}
