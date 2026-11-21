package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rebuilt Technician tab (front end only). There is no JavaScript test runner, so the structure is pinned as text;
 * the behaviour is exercised in Microsoft Edge by {@code scripts/live-checks/technician_portal_browser.py}.
 *
 * <p>Two kinds of test live here. The structure tests describe the new tab. The "untouched" tests prove that the work
 * stayed inside the Technician tab: they hash every part of the static files that is not the Technician tab and compare
 * it with the value recorded before the rebuild began, so a stray edit to another tab, to the header, or to a shared
 * style rule fails the build.
 */
class TechnicianPortalMarkupTest {

    private static final String CSS_START = "/* Technician portal (tp-) */";
    private static final String CSS_END = "/* End Technician portal */";

    /** Recorded from the files before the rebuild began (versions in the asset URLs are normalised away). */
    private static final String INDEX_OUTSIDE_TECHNICIAN_SHA =
            "701a138fec4b0968f33064eda1fca202d36755b8579bf9afcdaccdfcdd54f19b";
    private static final String CSS_OUTSIDE_TECHNICIAN_SHA =
            "e35d658cb9a1eef7b3b52db220174a1c475bf5f4844dddf6067600a481c8fe45";
    private static final String EMPLOYEE_JS_SHA =
            "1e566ced45dc60aa8f044c264710906e2fef658a960a2f66a2b8c270b14202b2";
    private static final String TEAM_LEAD_JS_SHA =
            "67e229f97c942646a5c974c56fb736e3652c25ee28a356407eab231ec880c7e5";

    private static String indexHtml;
    private static String appJs;
    private static String stylesCss;
    private static String techHtml;
    private static String techJs;

    @BeforeAll
    static void load() throws IOException {
        indexHtml = read("static/index.html");
        appJs = read("static/app.js");
        stylesCss = read("static/styles.css");
        int start = indexHtml.indexOf("<section id=\"console-technician\"");
        int end = indexHtml.indexOf("<section id=\"console-teamlead\"");
        assertThat(start).as("technician section").isGreaterThan(0);
        assertThat(end).as("team lead section").isGreaterThan(start);
        techHtml = indexHtml.substring(start, end);
        techJs = between(appJs, "// 7. Console 2: Technician Console Logic", "// 8. Console 3");
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = TechnicianPortalMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        assertThat(start).as(from).isGreaterThanOrEqualTo(0);
        int end = text.indexOf(to, start);
        assertThat(end).as(to).isGreaterThan(start);
        return text.substring(start, end);
    }

    private static String sha(String text) throws Exception {
        String normalised = text.replaceAll("\\?v=\\d+(\\.\\d+)*", "?v=X");
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private static String cssBlock() {
        int start = stylesCss.indexOf(CSS_START);
        int end = stylesCss.indexOf(CSS_END);
        assertThat(start).as("the Technician portal block of styles.css").isGreaterThanOrEqualTo(0);
        assertThat(end).as("the end marker of the Technician portal block").isGreaterThan(start);
        return stylesCss.substring(start, end);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Structure of the new tab
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("every element id the technician script and other tabs rely on is still in the tab")
    void oldIdsStillPresent() {
        List<String> ids = List.of(
                "tech-capacity-text", "tech-capacity-status", "tech-capacity-fill",
                "tech-stat-assigned", "tech-stat-investigating", "tech-stat-pending", "tech-stat-atrisk",
                "btn-tech-mode-my", "btn-tech-mode-team", "tech-queue-filter", "btn-refresh-tech-queue",
                "tech-queue-container");
        for (String id : ids) {
            assertThat(techHtml).as(id).contains("id=\"" + id + "\"");
        }
    }

    @Test
    @DisplayName("the team button keeps the label that says what it shows")
    void teamButtonLabelIsKept() {
        assertThat(techHtml).contains("Unassigned Team Tickets");
    }

    @Test
    @DisplayName("the tab has a spotlight panel, hidden until there is an investigation to show")
    void spotlightPanelExists() {
        int at = techHtml.indexOf("id=\"tp-spotlight\"");
        assertThat(at).as("spotlight container").isGreaterThan(0);
        String tag = techHtml.substring(techHtml.lastIndexOf('<', at), techHtml.indexOf('>', at));
        assertThat(tag).contains("hidden");
    }

    @Test
    @DisplayName("the mode toggle is a segmented control with a pressed state")
    void modeToggleIsSegmented() {
        for (String id : List.of("btn-tech-mode-my", "btn-tech-mode-team")) {
            int at = techHtml.indexOf("id=\"" + id + "\"");
            String tag = techHtml.substring(techHtml.lastIndexOf('<', at), techHtml.indexOf('>', at));
            assertThat(tag).as(id).contains("tp-seg-btn").contains("aria-pressed=");
        }
    }

    @Test
    @DisplayName("the queue is a table with the seven columns of the design")
    void queueIsATable() {
        for (String header : List.of("Ticket ID", "Priority", "Status", "Requester", "Date Raised", "Deadline", "Details")) {
            assertThat(techJs).as("column " + header).contains(header);
        }
        assertThat(techJs).contains("tp-table").contains("<table").contains("scope=\"col\"");
    }

    @Test
    @DisplayName("each ticket is its own tbody keyed for focus, with an expandable detail row")
    void rowsAreExpandable() {
        assertThat(techJs).contains("<tbody").contains("data-ticket-card=").contains("aria-expanded=")
                .contains("aria-controls=").contains("tpToggleRow");
        assertThat(appJs).contains("technician: {").contains("expandedId");
    }

    @Test
    @DisplayName("the helpers of the design exist and the load bands are named constants")
    void helpersAndConstantsExist() {
        for (String fn : List.of("tpSlaLine", "tpTimeLeft", "tpInitials", "tpSpotlightPick",
                "renderTechnicianSpotlight", "tpToggleRow")) {
            assertThat(techJs).as(fn).contains("function " + fn + "(");
        }
        assertThat(techJs).contains("TP_LOAD_MODERATE_PCT").contains("TP_LOAD_HEAVY_PCT");
        assertThat(techJs).contains("tp-load-badge--moderate");
    }

    @Test
    @DisplayName("the spotlight is limited to the technician's own tickets in progress")
    void spotlightRules() {
        String pick = between(techJs, "function tpSpotlightPick(", "\n}\n");
        assertThat(pick).contains("IN_PROGRESS").contains("BREACHED");
        assertThat(techJs).contains("STATE.techQueueMode === 'my'");
    }

    @Test
    @DisplayName("the mode buttons follow the state through aria-pressed, so Inspect Escalation still highlights the right one")
    void modeStateDrivesTheButtons() {
        String fn = between(techJs, "function setTechQueueMode(", "\n}\n");
        assertThat(fn).contains("aria-pressed").contains("tp-seg-btn");
    }

    @Test
    @DisplayName("focusRequestedTicket expands the ticket it scrolls to")
    void focusExpandsTheRow() {
        String fn = between(techJs, "function focusRequestedTicket(", "\n}\n");
        assertThat(fn).contains("expandedId");
    }

    @Test
    @DisplayName("every action button class is still produced and wired")
    void actionButtonsStillWired() {
        for (String action : List.of("ack", "investigate", "note", "reqinfo", "wrongdomain", "resolve", "suggest",
                "promote", "reassign")) {
            String cls = "btn-tech-" + action;
            assertThat(techJs.contains("'" + cls + "'") || techJs.contains(cls + "\""))
                    .as(cls + " rendered").isTrue();
            assertThat(techJs).as(cls + " wired").contains("querySelectorAll('." + cls + "')");
        }
    }

    @Test
    @DisplayName("the state-driven rules still decide which buttons appear (allowedActions, never the raw status)")
    void actionsFollowAllowedActions() {
        for (String action : List.of("acknowledge", "startInvestigation", "flagWrongDomain", "requestInfo", "resolve")) {
            assertThat(techJs).contains("actions.includes('" + action + "')");
        }
        assertThat(techJs).contains("t.promotable === true").contains("t.reassignable === true");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Safety rules of the project
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("no inline event handlers and no external resources in the tab's markup or script")
    void noInlineHandlersNoCdn() {
        Pattern inline = Pattern.compile("\\son[a-z]+\\s*=");
        assertThat(inline.matcher(techHtml).find()).as("inline handler in the tab markup").isFalse();
        assertThat(inline.matcher(techJs).find()).as("inline handler in the tab script").isFalse();
        assertThat(techHtml).doesNotContain("http://").doesNotContain("https://");
        assertThat(techJs).doesNotContain("https://").doesNotContain("cdn");
    }

    @Test
    @DisplayName("no ticket text is put into markup without escapeHtml")
    void ticketTextIsEscaped() {
        Matcher raw = Pattern.compile(
                "\\$\\{t\\.(title|description|reporterName|reporterDepartment|reporterId|assigneeName|assigneeId|category|status)\\}")
                .matcher(techJs);
        List<String> found = new ArrayList<>();
        while (raw.find()) {
            found.add(raw.group());
        }
        assertThat(found).as("unescaped interpolations").isEmpty();
    }

    @Test
    @DisplayName("every new style rule lives in the marked block and starts with the tp- prefix")
    void newStylesAreScoped() {
        String block = cssBlock().replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher selectors = Pattern.compile("([^{}]+)\\{").matcher(block);
        int seen = 0;
        while (selectors.find()) {
            String group = selectors.group(1).trim();
            if (group.startsWith("@") || group.matches("(from|to|\\d+%)")) {
                continue;
            }
            for (String selector : group.split(",")) {
                assertThat(selector.trim()).as("selector in the Technician portal block")
                        .matches("^[.#]tp-.*");
                seen++;
            }
        }
        assertThat(seen).as("the block defines rules").isGreaterThan(10);
        assertThat(stylesCss.indexOf(CSS_START)).isEqualTo(stylesCss.lastIndexOf(CSS_START));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The work stayed inside the Technician tab
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("shared style rules used by other tabs are unchanged")
    void sharedRulesUnchanged() {
        assertThat(stylesCss).contains(""
                + ".kpi-card {\n  display: flex;\n  flex-direction: column;\n  gap: 0.35rem;\n  background: #ffffff;\n"
                + "  border: 1px solid var(--border);\n  border-radius: var(--radius-md);\n  padding: 1rem;\n"
                + "  box-shadow: var(--shadow-sm);\n}");
        assertThat(stylesCss).contains(""
                + ".capacity-bar {\n  width: 100%;\n  height: 8px;\n  background: #e2e8f0;\n"
                + "  border-radius: var(--radius-full);\n  overflow: hidden;\n  margin-top: 0.35rem;\n}");
        assertThat(stylesCss).contains(""
                + ".capacity-fill {\n  height: 100%;\n  border-radius: var(--radius-full);\n"
                + "  background: linear-gradient(90deg, #0284c7 0%, #72c3e8 100%);\n  transition: width 0.3s ease;\n}\n"
                + ".capacity-fill.high {\n  background: linear-gradient(90deg, #f4af34 0%, #ea3f26 100%);\n}");
        assertThat(stylesCss).contains(""
                + ".sla-timer-pill {\n  display: inline-flex;\n  align-items: center;\n  gap: 0.35rem;\n"
                + "  padding: 0.15rem 0.5rem;\n  border-radius: var(--radius-full);\n  font-family: var(--font-mono);\n"
                + "  font-size: 0.7rem;\n  font-weight: 700;\n  border: 1px solid transparent;");
        assertThat(stylesCss).contains(""
                + ".ticket-focus {\n  outline: 3px solid var(--primary);\n  outline-offset: 2px;\n"
                + "  transition: outline-color 0.3s ease;\n}");
    }

    @Test
    @DisplayName("index.html outside the Technician tab is unchanged (asset versions aside)")
    void restOfIndexUnchanged() throws Exception {
        String start = "<section id=\"console-technician\"";
        String end = "<section id=\"console-teamlead\"";
        String rest = indexHtml.substring(0, indexHtml.indexOf(start)) + indexHtml.substring(indexHtml.indexOf(end));
        assertThat(sha(rest)).isEqualTo(INDEX_OUTSIDE_TECHNICIAN_SHA);
    }

    @Test
    @DisplayName("styles.css outside the Technician portal block is unchanged")
    void restOfStylesUnchanged() throws Exception {
        int start = stylesCss.indexOf(CSS_START);
        String rest = start < 0
                ? stylesCss
                : stylesCss.substring(0, start) + stylesCss.substring(stylesCss.indexOf(CSS_END) + CSS_END.length());
        assertThat(sha(rest.stripTrailing())).isEqualTo(CSS_OUTSIDE_TECHNICIAN_SHA);
    }

    @Test
    @DisplayName("the Employee and Team Lead scripts are unchanged")
    void otherTabScriptsUnchanged() throws Exception {
        String employee = between(appJs, "// 6. Console 1: Employee Portal Logic", "// 7. Console 2");
        String teamLead = between(appJs, "// 8. Console 3: Team Lead Console Logic", "// 9. Console 4");
        assertThat(sha(employee)).as("employee script").isEqualTo(EMPLOYEE_JS_SHA);
        assertThat(sha(teamLead)).as("team lead script").isEqualTo(TEAM_LEAD_JS_SHA);
    }
}
