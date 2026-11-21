package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Suggested resolutions, Phase K4 (ADR-043): the front end. There is no JavaScript test runner, so the structure the
 * behaviour depends on is pinned as text; the behaviour is exercised in a real browser by
 * {@code scripts/live-checks/suggestions_browser.py}.
 */
class SuggestedResolutionMarkupTest {

    private static String appJs;
    private static String stylesCss;
    private static String indexHtml;

    @BeforeAll
    static void load() throws IOException {
        appJs = read("static/app.js");
        stylesCss = read("static/styles.css");
        indexHtml = read("static/index.html");
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = SuggestedResolutionMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
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

    @Test
    @DisplayName("Technician cards offer 'Suggested fixes' on open tickets and 'Promote' only when the server says promotable")
    void cardButtons() {
        String render = functionBody("function renderTechnicianQueue(");
        String permissions = functionBody("function tpPermissions(");
        String actions = functionBody("function tpActionsHtml(");
        assertThat(permissions).contains("canSuggest: !isResolved");
        assertThat(permissions).contains("canPromote: isResolved && t.promotable === true");
        assertThat(actions).contains("p.canSuggest ? button('btn-tech-suggest', 'btn-secondary', 'Suggested fixes')");
        assertThat(actions).contains("button('btn-tech-promote', 'btn-primary', 'Promote to knowledge base', ` data-title=\"${escapeHtml(t.title)}\"`)");
        assertThat(render).contains("openSuggestionsModal(b.getAttribute('data-id'))");
        assertThat(render).contains("openPromoteModal(b.getAttribute('data-id'), b.getAttribute('data-title'))");
    }

    @Test
    @DisplayName("Suggestions are loaded on demand: drawing the list never asks for them")
    void loadedOnDemand() {
        assertThat(functionBody("function renderTechnicianQueue(")).doesNotContain("suggested-resolutions");
        assertThat(functionBody("async function loadTechnicianQueue(")).doesNotContain("suggested-resolutions");
        assertThat(functionBody("function openSuggestionsModal(")).contains("loadSuggestions(incidentId);");
        assertThat(functionBody("async function loadSuggestions(")).contains("/suggested-resolutions`");
    }

    @Test
    @DisplayName("A late answer for a closed or replaced dialog is discarded")
    void lateAnswersDiscarded() {
        String load = functionBody("async function loadSuggestions(");
        assertThat(load).contains("const seq = ++STATE.suggestions.requestSeq;");
        assertThat(load).contains("if (seq !== STATE.suggestions.requestSeq || !suggestionsDialogIsOpen(incidentId)) return;");
        assertThat(appJs).contains("suggestions: { incidentId: null, requestSeq: 0, promoteInFlight: false }");
    }

    @Test
    @DisplayName("Every value of a suggestion card goes through escapeHtml or a number formatter")
    void everythingEscaped() {
        String card = functionBody("function suggestionCardHtml(") + functionBody("function suggestionSourceHtml(");
        Matcher raw = Pattern.compile("\\$\\{s\\.[A-Za-z]+\\}").matcher(card);
        assertThat(raw.find()).as("a raw ${s.field} interpolation").isFalse();
        assertThat(card).contains("escapeHtml(s.rootCause)").contains("escapeHtml(s.stepsTaken)")
                .contains("escapeHtml(s.preventiveAction)").contains("escapeHtml(t)").contains("escapeHtml(s.sourceId)")
                .contains("formatSuggestionScore(s.similarity)").contains("suggestionVoteCount(s.workedVotes)");
        assertThat(functionBody("function openPromoteModal(")).contains("escapeHtml(incidentId)").contains("escapeHtml(ticketTitle || '')");
    }

    @Test
    @DisplayName("Votes and promotions go through apiCall (in-flight guard, refresh after a refused write)")
    void writesThroughApiCall() {
        String vote = functionBody("async function castSuggestionVote(");
        assertThat(vote).contains("await apiCall(`/api/technician/incidents/${encodeURIComponent(incidentId)}/suggested-resolutions/votes`")
                .contains("method: 'POST'").contains("JSON.stringify({ sourceType, sourceId, worked })");
        assertThat(vote).as("the list is drawn again after every vote").contains("loadSuggestions(incidentId)");
        String promote = functionBody("function openPromoteModal(");
        assertThat(promote).contains("await apiCall('/api/technician/knowledge-articles'")
                .contains("JSON.stringify({ sourceIncidentId: incidentId, title: title || null })");
        assertThat(promote).as("Enter pressed twice is one promotion")
                .contains("if (STATE.suggestions.promoteInFlight) return;").contains("STATE.suggestions.promoteInFlight = false;");
        assertThat(appJs).contains("if (refreshAfterFailureScheduled || !(status === 400 || status === 403 || status === 409");
    }

    @Test
    @DisplayName("Refusals get friendly messages: 409 already promoted / not resolved, 404 gone, 403 not allowed")
    void friendlyMessages() {
        String messages = appJs.substring(appJs.indexOf("const SUGGESTION_FAILURES"), appJs.indexOf("function formatSuggestionScore("));
        assertThat(messages).contains("ERR_ALREADY_PROMOTED").contains("ERR_NOT_RESOLVED").contains("ERR_NOT_FOUND")
                .contains("err.status === 403").contains("err.status === 404");
    }

    @Test
    @DisplayName("Vote buttons carry the tech action prefix and the source id, so keyboard focus returns to their twin")
    void focusReturns() {
        String card = functionBody("function suggestionCardHtml(");
        assertThat(card).contains("btn-tech-vote-worked\" data-id=\"${id}\"").contains("btn-tech-vote-failed\" data-id=\"${id}\"");
        assertThat(functionBody("function restoreFocus(")).contains("/^btn-(tech|emp|admin|teamlead|triage)-/");
        assertThat(functionBody("function openSuggestionsModal(")).contains("openModal(").contains("data-close-modal");
    }

    @Test
    @DisplayName("The styles exist; no inline handlers in the new code; the asset version is 4.2")
    void stylesAndVersion() {
        for (String cls : new String[] {".suggestion-list", ".suggestion-card", ".suggestion-card-header", ".suggestion-source-kb",
                ".suggestion-source-ticket", ".suggestion-score", ".suggestion-fields", ".suggestion-term", ".suggestion-votes",
                ".suggestion-count"}) {
            assertThat(stylesCss).as(cls).contains(cls + " {");
        }
        String section = appJs.substring(appJs.indexOf("// 16. Suggested Resolutions"));
        assertThat(section).doesNotContainPattern("\\son[a-z]+=");
        assertThat(indexHtml).contains("/styles.css?v=5.5").contains("/app.js?v=5.5");
    }
}
