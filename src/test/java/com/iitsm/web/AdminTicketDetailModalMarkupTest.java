package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the contract between the admin ticket inspection dialog markup, its stylesheet and the script that
 * fills it, plus the offline (no CDN) and CSP (no inline handlers) rules of the embedded single-page app.
 */
class AdminTicketDetailModalMarkupTest {

    private static final List<String> REQUIRED_IDS = List.of(
            "admin-ticket-detail-modal", "ticket-detail-id", "ticket-detail-pills", "ticket-detail-title",
            "btn-close-ticket-detail", "ticket-detail-body", "ticket-detail-loading", "ticket-detail-error",
            "ticket-detail-content", "td-description", "td-reporter", "td-service", "td-assignment", "td-lifecycle",
            "td-sla", "td-priority-summary", "td-priority-factors", "td-priority-overrides", "td-notes",
            "td-resolution", "td-feedback", "td-linked", "td-audit", "btn-ticket-detail-override",
            "btn-ticket-detail-reassign", "btn-ticket-detail-close");

    private static final List<String> REQUIRED_CSS_CLASSES = List.of(
            "ticket-detail-dialog", "ticket-detail-body", "ticket-detail-footer", "td-grid", "td-card", "td-dl",
            "td-metrics", "td-timeline", "td-bar-fill", "ticket-link", "cursor-pointer", "modal-open",
            "badge-sla-on-track", "badge-sla-at-risk", "badge-sla-warning", "badge-sla-breached", "badge-sla-met",
            "badge-sla-paused", "badge-tier-4", "badge-tier-1", "badge-status-classified", "badge-status-acknowledged",
            "badge-status-verified", ".table td.text-red", ".table td.text-cyan");

    private static final Pattern SCRIPT_ELEMENT_REFERENCE = Pattern.compile(
            "(?:getElementById|tdSetHtml|tdSetText)\\('(admin-ticket-detail-modal|ticket-detail-[a-z-]+|td-[a-z-]+"
                    + "|btn-[a-z-]*ticket-detail[a-z-]*)'");

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
        try (InputStream stream = AdminTicketDetailModalMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Dialog markup declares every element id the script populates")
    void markupDeclaresRequiredIds() {
        for (String id : REQUIRED_IDS) {
            assertThat(indexHtml).as("element id " + id).contains("id=\"" + id + "\"");
        }
    }

    @Test
    @DisplayName("Dialog is hidden by default and exposed to assistive technology as a modal dialog")
    void dialogIsHiddenAndAccessible() {
        Pattern dialog = Pattern.compile("<div id=\"admin-ticket-detail-modal\"[^>]*>");
        var matcher = dialog.matcher(indexHtml);
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).contains("hidden").contains("role=\"dialog\"").contains("aria-modal=\"true\"");
    }

    @Test
    @DisplayName("Stylesheet defines every class the dialog relies on")
    void stylesheetDefinesRequiredClasses() {
        for (String cssClass : REQUIRED_CSS_CLASSES) {
            assertThat(stylesCss).as("css class " + cssClass).contains(cssClass);
        }
    }

    @Test
    @DisplayName("No inline event handlers (CSP script-src 'self') and no external resources (offline compliance)")
    void staysCspAndOfflineCompliant() {
        assertThat(indexHtml).doesNotContainPattern("\\son[a-z]+\\s*=");
        assertThat(indexHtml).doesNotContain("http://").doesNotContain("https://");
        assertThat(stylesCss).doesNotContain("@import").doesNotContain("http://").doesNotContain("https://");
    }

    @Test
    @DisplayName("Every element id the script looks up for the dialog exists in the markup")
    void scriptOnlyReferencesElementsThatExist() {
        var matcher = SCRIPT_ELEMENT_REFERENCE.matcher(appJs);
        int references = 0;
        while (matcher.find()) {
            references++;
            assertThat(indexHtml).as("script references missing element id " + matcher.group(1))
                    .contains("id=\"" + matcher.group(1) + "\"");
        }
        assertThat(references).as("script should reference the dialog elements").isGreaterThan(REQUIRED_IDS.size());
    }

    @Test
    @DisplayName("Command Centre table reads the fields AdminIncidentView really exposes")
    void adminTableReadsRealApiFields() {
        assertThat(appJs).contains("t.priorityDecision", "sla.resolutionDueAt", "sla.status === 'BREACHED'");
        assertThat(appJs).doesNotContain("t.slaDeadline", "t.slaBreached");
    }

    @Test
    @DisplayName("Script wires the clickable ticket id, the dialog entry point and Escape handling")
    void scriptWiresDialogEntryPoints() {
        assertThat(appJs).contains("btn-admin-view-ticket", "openAdminTicketDetailModal", "closeAdminTicketDetailModal",
                "encodeURIComponent", "'Escape'", "setupTicketDetailListeners();");
    }
}
