package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structure of the Team Lead's "current tickets of a team member" window (ADR-054). There is no JavaScript test runner,
 * so the source is pinned as text: the roster card is clickable, the window only reads, it lists the tickets the
 * technician holds that the employee has not yet confirmed, and it can be closed.
 */
class TeamMemberTicketsMarkupTest {

    private static String appJs;
    private static String stylesCss;
    private static String section20;

    @BeforeAll
    static void load() throws IOException {
        appJs = read("/static/app.js");
        stylesCss = read("/static/styles.css");
        int start = appJs.indexOf("// 20. Team Lead: the current tickets of one team member");
        assertThat(start).as("section 20 of app.js").isGreaterThan(0);
        section20 = appJs.substring(start);
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = TeamMemberTicketsMarkupTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("classpath resource " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        assertThat(start).as(from).isGreaterThanOrEqualTo(0);
        int end = text.indexOf(to, start);
        assertThat(end).as(to).isGreaterThan(start);
        return text.substring(start, end);
    }

    @Test
    @DisplayName("Each roster card is keyboard-reachable and names the technician it opens")
    void rosterCardIsClickable() {
        String roster = between(appJs, "function renderTeamRoster(", "// 9. Console 4");

        assertThat(roster).contains("tlm-card").contains("role=\"button\"").contains("tabindex=\"0\"");
        assertThat(roster).contains("data-id=\"${escapeHtml(tech.id)}\"").contains("data-name=\"${escapeHtml(tech.name)}\"");
    }

    @Test
    @DisplayName("The roster card keeps its look: same title, load badge, skills, role and ticket count")
    void rosterLookIsUnchanged() {
        String roster = between(appJs, "function renderTeamRoster(", "// 9. Console 4");

        assertThat(roster).contains("<h4 class=\"font-bold text-main text-sm\">${escapeHtml(tech.name)}</h4>");
        assertThat(roster).contains("${pct}% Load").contains("Skills:").contains("Role:").contains("Active Tickets:");
    }

    @Test
    @DisplayName("The window only reads: one GET, no write method and no action button")
    void windowIsReadOnly() {
        assertThat(section20).contains("apiCall('/api/technician/team-queue')");
        assertThat(section20).doesNotContain("method:").doesNotContain("'POST'").doesNotContain("'PUT'")
                .doesNotContain("'PATCH'").doesNotContain("'DELETE'");
        assertThat(section20).doesNotContain("btn-teamlead-assign").doesNotContain("btn-teamlead-reassign")
                .doesNotContain("openTeamReassignModal").doesNotContain("btn-tech-").doesNotContain("btn-admin-");
    }

    @Test
    @DisplayName("The only buttons in the window are the cross and the footer Close")
    void onlyCloseButtons() {
        int buttons = section20.split("<button", -1).length - 1;
        int closeButtons = section20.split("data-close-modal", -1).length - 1;

        assertThat(buttons).isEqualTo(2);
        assertThat(closeButtons).isEqualTo(2);
        assertThat(section20).contains("&times;").contains("aria-label=\"Close\"");
    }

    @Test
    @DisplayName("It lists the technician's own tickets that the employee has not yet confirmed (VERIFIED and CLOSED are left out)")
    void filtersToCurrentTicketsOfTheTechnician() {
        assertThat(section20).contains("const TLM_CONFIRMED_STATUSES = ['VERIFIED', 'CLOSED'];");
        assertThat(section20).contains("t.assigneeId === techId && !TLM_CONFIRMED_STATUSES.includes(t.status)");
    }

    @Test
    @DisplayName("Each ticket shows its id, name, title, type, status, priority and a progress bar")
    void rowShowsIdNameTypeAndProgress() {
        assertThat(section20).contains("escapeHtml(t.id)").contains("ticketNameHtml(t)").contains("escapeHtml(t.title)");
        assertThat(section20).contains("tlmCategory(t.category)").contains("tpStatusChip(t, line)")
                .contains("tpPriorityChip(t.priority)");
        assertThat(section20).contains("tlm-progress-fill").contains("TLM_STEP_OF_STATUS");
        assertThat(section20).contains("RESOLVED: 4").contains("waiting for the employee to confirm");
    }

    @Test
    @DisplayName("The description, notes and resolution are in a collapsible part of each ticket")
    void detailsAreCollapsible() {
        assertThat(section20).contains("<details class=\"tlm-details\">").contains("t.description")
                .contains("t.notes").contains("t.resolution");
    }

    @Test
    @DisplayName("Every dynamic value is escaped and no inline handler is used")
    void escapesAndNoInlineHandlers() {
        for (String field : new String[] {"t.title", "t.description", "n.noteText", "n.authorName", "resolution.rootCause",
            "resolution.stepsTaken", "resolution.preventiveAction", "error.message", "name"}) {
            assertThat(section20).as(field).contains("escapeHtml(" + field);
        }
        assertThat(section20).doesNotContainPattern("\\son[a-z]+=\"");
        assertThat(section20).doesNotContain("innerHTML = t.").doesNotContain("${t.title}").doesNotContain("${t.description}");
    }

    @Test
    @DisplayName("The window closes with the cross, Escape, a click outside and sign-out")
    void closesInEveryWay() {
        assertThat(section20).contains("event.key === 'Escape' && tlmDialogOpen()")
                .contains("event.target === container && tlmDialogOpen()");
        String signOut = between(appJs, "function handleSignOut(", "// 4. Tab Navigation");
        assertThat(signOut).contains("closeTeamMemberTickets();");
        assertThat(appJs).contains("setupTeamMemberListeners();");
    }

    @Test
    @DisplayName("A late answer is discarded, and failure and empty states are shown")
    void lateAnswersAndStates() {
        assertThat(section20).contains("const seq = ++tlmRequestSeq;").contains("seq !== tlmRequestSeq");
        assertThat(section20).contains("This team member has no current tickets.").contains("Could not load the tickets");
        assertThat(section20).contains("Loading tickets...");
    }

    @Test
    @DisplayName("The window is wide enough for a ticket row and has its own styles")
    void hasStyles() {
        assertThat(stylesCss).contains(".modal-content:has(.tlm-dialog) { max-width: 860px; }")
                .contains(".tlm-card:focus-visible").contains(".tlm-progress-fill");
    }
}
