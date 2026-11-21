package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract of the admin triage card in the static web app (ADR-029, Phase C2). The page has no test framework, so this
 * checks the source: the card is built per ticket type, only a technician's flag can be rejected, the reject call goes to
 * the new endpoint, and nothing is written into the page without escaping.
 */
class TriageCardMarkupTest {

    private static String appJs;
    private static String indexHtml;

    @BeforeAll
    static void load() throws IOException {
        appJs = read("/static/app.js");
        indexHtml = read("/static/index.html");
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = TriageCardMarkupTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("classpath resource " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String functionBody(String name) {
        int start = appJs.indexOf("function " + name + "(");
        assertThat(start).as("function " + name).isGreaterThanOrEqualTo(0);
        int next = appJs.indexOf("\nfunction ", start + 1);
        int nextAsync = appJs.indexOf("\nasync function ", start + 1);
        int end = java.util.stream.IntStream.of(next, nextAsync).filter(i -> i > 0).min().orElse(appJs.length());
        return appJs.substring(start, end);
    }

    @Test
    @DisplayName("Every triage entry is drawn by one function that branches on the ticket type")
    void cardIsBuiltPerType() {
        assertThat(functionBody("loadAdminTriageQueue")).contains("triage.map(triageCardHtml)");
        String card = functionBody("triageCardHtml");
        assertThat(card).contains("item.triageType === 'WRONG_DOMAIN'")
                .contains("Flagged by technician").contains("Low confidence")
                .contains("Classifier suggests").contains("item.suggestions");
    }

    @Test
    @DisplayName("The old label that called every triage ticket out-of-scope is gone")
    void oldLabelIsGone() {
        assertThat(appJs).doesNotContain("Flagged Out-of-Scope");
    }

    @Test
    @DisplayName("Only a technician's flag can be rejected: the Reject button is drawn for WRONG_DOMAIN entries only")
    void rejectButtonOnlyForFlaggedTickets() {
        String card = functionBody("triageCardHtml");
        assertThat(Pattern.compile("flagged\\s*\\?\\s*`<button[^`]*btn-triage-reject").matcher(card).find())
                .as("the reject button sits inside the flagged branch").isTrue();
        assertThat(card).contains("data-request-id=\"${escapeHtml(item.requestId)}\"");
    }

    @Test
    @DisplayName("Rejecting posts the reason to the reject endpoint and refreshes the queue")
    void rejectCallsTheEndpoint() {
        assertThat(functionBody("loadAdminTriageQueue")).contains(".btn-triage-reject").contains("openRejectReassignmentModal(");
        String modal = functionBody("openRejectReassignmentModal");
        assertThat(modal).contains("/api/admin/reassignments/${encodeURIComponent(requestId)}/reject")
                .contains("method: 'POST'").contains("JSON.stringify({ reason })")
                .contains("loadAdminTriageQueue()").contains("form-modal-reject-reassignment").contains("required");
    }

    @Test
    @DisplayName("Dynamic values in the card and the dialog are escaped (no HTML injection from a technician's reason)")
    void dynamicValuesAreEscaped() {
        String card = functionBody("triageCardHtml");
        for (String value : new String[] {"item.incidentId", "item.title", "item.reason", "item.category", "item.requestingTechnicianId"}) {
            assertThat(card).as(value).contains("escapeHtml(" + value);
        }
        assertThat(card).doesNotContain("${item.reason}").doesNotContain("${item.incidentId}").doesNotContain("${item.title}");
        assertThat(functionBody("openRejectReassignmentModal")).contains("escapeHtml(incidentId)");
    }

    @Test
    @DisplayName("The asset version was bumped with the change, so browsers do not keep the old script")
    void assetVersionIsBumped() {
        assertThat(indexHtml).contains("/styles.css?v=5.2").contains("/app.js?v=5.2")
                .doesNotContain("?v=3.5");
    }
}
