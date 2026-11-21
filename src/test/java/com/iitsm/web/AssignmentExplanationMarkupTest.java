package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Why this technician" in the admin ticket dialog (ADR-044, Phase L2). There is no JavaScript test runner, so the
 * structure is pinned as text; the behaviour is exercised in a real browser by
 * {@code scripts/live-checks/assignment_explanation_browser.py}.
 */
class AssignmentExplanationMarkupTest {

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
        try (InputStream stream = AssignmentExplanationMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String functionBody(String signature) {
        int start = appJs.indexOf(signature);
        assertThat(start).as(signature).isGreaterThanOrEqualTo(0);
        int end = appJs.indexOf("\nfunction ", start + 1);
        return appJs.substring(start, end < 0 ? appJs.length() : end);
    }

    @Test
    @DisplayName("The dialog has a 'Why This Technician' section inside the admin ticket dialog, after the priority matrix")
    void sectionExists() {
        int dialog = indexHtml.indexOf("id=\"admin-ticket-detail-modal\"");
        int priority = indexHtml.indexOf("id=\"td-priority-overrides\"");
        int why = indexHtml.indexOf("id=\"td-assignment-why\"");
        assertThat(dialog).isPositive();
        assertThat(why).isGreaterThan(priority).isGreaterThan(dialog);
        assertThat(indexHtml).contains(">Why This Technician</h3>");
    }

    @Test
    @DisplayName("The dialog renders the decisions on every open and shows an empty state for older tickets")
    void renderedWithEmptyState() {
        assertThat(functionBody("function renderAdminTicketDetail(")).contains("renderTicketDetailAssignmentWhy(d);");
        String render = functionBody("function renderTicketDetailAssignmentWhy(");
        assertThat(render).contains("d.assignmentDecisions || []");
        assertThat(render).contains("tdEmpty('No assignment record for this ticket.')");
    }

    @Test
    @DisplayName("Every dynamic value is escaped: names and ids through tdActor, texts through tdEsc, numbers through tdNum")
    void everyValueIsEscaped() {
        String decision = functionBody("function tdWhyDecision(");
        assertThat(decision).contains("tdEsc(decision.strategyName)", "tdText(decision.poolLabel)",
                "tdActor(decision.actorId, decision.actorName)", "tdEsc(decision.summary)",
                "escapeHtml(formatDate(decision.decidedAt))");
        String row = functionBody("function tdWhyCandidateRow(");
        assertThat(row).contains("tdActor(candidate.technicianId, candidate.technicianName)",
                "tdEsc(candidate.rank)", "tdNum(candidate.total, 3)");
        String cell = functionBody("function tdWhyFactorCell(");
        assertThat(cell).contains("tdNum(factor.rawScore, 2)", "tdNum(factor.weight, 2)", "tdNum(factor.contribution, 3)",
                "title=\"${tdEsc(factor.detail)}\"", "${tdEsc(factor.detail)}");
        assertThat(functionBody("function tdWhyCandidateTable(")).contains("tdEsc(tdWhyFactorLabel(key))",
                "tdEsc(decision.candidateCount)");
    }

    @Test
    @DisplayName("The chosen row is highlighted, penalties and tie-breaks are marked, and a cut ranking says so")
    void chosenPenaltyTieBreakAndCut() {
        assertThat(functionBody("function tdWhyCandidateRow(")).contains("chosen ? 'td-why-chosen' : ''");
        String cell = functionBody("function tdWhyFactorCell(");
        assertThat(cell).contains("weight < 0 ? ' td-why-penalty'", "weight === 0 ? ' td-why-tiebreak'");
        assertThat(functionBody("function tdWhyCandidateTable(")).contains("Showing the best");
        assertThat(stylesCss).contains(".td-why-decision", ".td-why-table tr.td-why-chosen td",
                ".table td.td-why-penalty", ".table td.td-why-tiebreak", ".td-why-detail", ".td-why-note");
    }

    @Test
    @DisplayName("No inline handlers in the new code, and the asset version is 4.2")
    void cspAndAssetVersion() {
        int start = appJs.indexOf("// --- Why this technician (ADR-044)");
        int end = appJs.indexOf("function renderTicketDetailAssignmentWhy(");
        assertThat(start).isPositive();
        assertThat(appJs.substring(start, end)).doesNotContainPattern("\\son[a-z]+=");
        assertThat(indexHtml).contains("/styles.css?v=5.5").contains("/app.js?v=5.5");
    }
}
