package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The "Assignment Strategy Comparison" panel of the Simulation tab (ADR-044, Phase L3). There is no JavaScript test
 * runner, so the structure is pinned as text; the behaviour is exercised in a real browser by
 * {@code scripts/live-checks/strategy_comparison_check.py}.
 */
class StrategyComparisonMarkupTest {

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
        try (InputStream stream = StrategyComparisonMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
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
    @DisplayName("The panel is in the Simulation tab with a form (seed, tickets, replications), a run button and the result areas")
    void panelMarkup() {
        int tab = indexHtml.indexOf("id=\"console-simulation\"");
        int reports = indexHtml.indexOf("id=\"console-reports\"");
        int card = indexHtml.indexOf("id=\"strategy-comparison-card\"");
        assertThat(card).isGreaterThan(tab).isLessThan(reports);
        for (String id : new String[] {"form-strategy-comparison", "cmp-seed", "cmp-tickets", "cmp-replications",
                "btn-run-comparison", "cmp-status", "cmp-notice", "cmp-results", "cmp-assumptions"}) {
            assertThat(indexHtml).as(id).contains("id=\"" + id + "\"");
        }
        assertThat(indexHtml).contains("Assignment Strategy Comparison").contains("Nothing is saved, sent or changed");
        assertThat(indexHtml).contains("<label class=\"form-label\" for=\"cmp-seed\">", "<label class=\"form-label\" for=\"cmp-tickets\">",
                "<label class=\"form-label\" for=\"cmp-replications\">");
        assertThat(indexHtml).contains("role=\"status\" aria-live=\"polite\"");
    }

    @Test
    @DisplayName("The form is submitted by script (no inline handler), once at a time, and the limits are checked before sending")
    void submitAndGuard() {
        assertThat(functionBody("function setupStrategyComparisonListeners(")).contains("addEventListener('submit'", "event.preventDefault()");
        String run = functionBody("async function runStrategyComparison(");
        assertThat(run).contains("if (strategyComparisonInFlight) return;", "strategyComparisonInFlight = true;",
                "strategyComparisonInFlight = false;", "button.disabled = true;", "finally {");
        assertThat(run).contains("apiCall('/api/simulation/strategy-comparison', { method: 'POST'");
        assertThat(functionBody("function cmpReadInputs(")).contains("CMP_MAX_TICKETS", "CMP_MAX_REPLICATIONS", "CMP_MAX_PRODUCT",
                "Number.isSafeInteger(seed)");
        assertThat(appJs).contains("const CMP_MAX_TICKETS = 5000;", "const CMP_MAX_REPLICATIONS = 50;", "const CMP_MAX_PRODUCT = 25000;");
        assertThat(functionBody("function setupEventListeners(")).contains("setupStrategyComparisonListeners();");
    }

    @Test
    @DisplayName("Errors are friendly: a refused value shows the server's explanation, a 403 says who may run it, and the toast repeats it")
    void friendlyErrors() {
        assertThat(functionBody("function cmpFriendlyError(")).contains("err.status === 400", "The comparison was not run.",
                "err.status === 403", "Only administrators can run the strategy comparison.");
        assertThat(functionBody("async function runStrategyComparison(")).contains("cmpSetStatus(cmpFriendlyError(err), true);",
                "showToast(cmpFriendlyError(err), 'error');");
        assertThat(functionBody("function cmpSetStatus(")).contains("cmp-status-error");
    }

    @Test
    @DisplayName("The notice says these are simulated results, not production data; the assumptions are listed")
    void noticeAndAssumptions() {
        String render = functionBody("function renderStrategyComparison(");
        assertThat(render).contains("data.notice", "Model assumptions", "data.assumptions");
        assertThat(render).contains("cmp-notice", "cmp-assumption-list");
        assertThat(stylesCss).contains(".cmp-notice");
    }

    @Test
    @DisplayName("The table marks the best value of each column and the differences larger than chance")
    void bestAndSignificance() {
        assertThat(functionBody("function cmpCell(")).contains("data.winners[metric.key]", "' cmp-best'");
        assertThat(functionBody("function cmpDifferenceHtml(")).contains("view.distinguishable", "cmp-sig", "&Delta;");
        assertThat(functionBody("function cmpResultsTable(")).contains("data.metrics", "data.strategies");
        assertThat(stylesCss).contains(".table td.cmp-best", ".cmp-sig", ".cmp-table", ".cmp-form", ".cmp-status", ".cmp-hint");
    }

    @Test
    @DisplayName("Every dynamic value is escaped: names, labels, units, numbers, assumptions, parameters")
    void everythingIsEscaped() {
        assertThat(functionBody("function cmpRow(")).contains("tdEsc(strategy.name)");
        assertThat(functionBody("function cmpResultsTable(")).contains("tdEsc(m.label)", "tdEsc(m.unit)");
        assertThat(functionBody("function cmpCell(")).contains("tdEsc(cmpFormat(metric.unit, view.mean))",
                "tdEsc(cmpFormatSpread(metric.unit, view.ci95HalfWidth, false))");
        assertThat(functionBody("function cmpDifferenceHtml(")).contains("tdEsc(reference)",
                "tdEsc(cmpFormatSpread(metric.unit, view.differenceVsReference, true))");
        assertThat(functionBody("function cmpSummaryLine(")).contains("tdEsc(p.seed)", "tdEsc(p.ticketCount)", "tdEsc(p.replications)",
                "tdEsc(p.arrivalsPerHour)", "tdEsc(data.technicianCount)", "tdEsc(data.runMillis)", "tdEsc(data.referenceStrategy)");
        assertThat(functionBody("function renderStrategyComparison(")).contains("tdEsc(a)");
        assertThat(functionBody("function renderStrategyComparison(")).contains("notice.textContent = data.notice");
    }

    @Test
    @DisplayName("Signing out clears the result (it is administrator data); no inline handlers and no CDN; asset version 4.2")
    void signOutCspAndAssetVersion() {
        assertThat(functionBody("function handleSignOut(")).contains("resetStrategyComparison();");
        assertThat(functionBody("function resetStrategyComparison(")).contains("cmp-results", "cmp-assumptions", "cmp-notice", "cmp-status");
        int start = appJs.indexOf("// 17. Assignment strategy comparison (ADR-044, Phase L3)");
        assertThat(start).isPositive();
        assertThat(appJs.substring(start)).doesNotContainPattern("\\son[a-z]+=").doesNotContain("http://", "https://");
        int card = indexHtml.indexOf("id=\"strategy-comparison-card\"");
        int cardEnd = indexHtml.indexOf("id=\"console-reports\"");
        assertThat(indexHtml.substring(card, cardEnd)).doesNotContainPattern("\\son[a-z]+=");
        assertThat(indexHtml).contains("/styles.css?v=5.6").contains("/app.js?v=5.6");
    }
}
