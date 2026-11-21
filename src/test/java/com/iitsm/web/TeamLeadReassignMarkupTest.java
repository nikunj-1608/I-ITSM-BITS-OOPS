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
 * Structure of the team lead's reassignment dialog and of the buttons fixed in Phase M4 (ADR-045). There is no JavaScript
 * test runner, so the source is pinned as text; the behaviour is checked in Edge by
 * {@code scripts/live-checks/team_lead_reassign_browser.py}.
 */
class TeamLeadReassignMarkupTest {

    private static String indexHtml;
    private static String appJs;
    private static String stylesCss;

    @BeforeAll
    static void load() throws IOException {
        indexHtml = read("/static/index.html");
        appJs = read("/static/app.js");
        stylesCss = read("/static/styles.css");
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = TeamLeadReassignMarkupTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("classpath resource " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String body(String marker) {
        int start = appJs.indexOf(marker);
        assertThat(start).as(marker).isGreaterThanOrEqualTo(0);
        int next = appJs.indexOf("\nfunction ", start + marker.length());
        int nextAsync = appJs.indexOf("\nasync function ", start + marker.length());
        int end = java.util.stream.IntStream.of(next, nextAsync).filter(i -> i > 0).min().orElse(appJs.length());
        return appJs.substring(start, end);
    }

    // ---- Phase M3: the dialog ------------------------------------------------------------------------------------

    @Test
    @DisplayName("The dialog reads the live roster and the ticket, and posts to the lead's reassign endpoint")
    void dialogUsesTheRealEndpoints() {
        String dialog = body("async function openTeamReassignModal(");

        assertThat(dialog).contains("apiCall('/api/admin/technicians')");
        assertThat(dialog).contains("/api/technician/incidents/${encodeURIComponent(ticketId)}");
        assertThat(dialog).contains("/api/technician/incidents/${encodeURIComponent(ticket.id)}/reassign");
        assertThat(dialog).contains("JSON.stringify({ technicianId: chosen.value, reason })");
    }

    @Test
    @DisplayName("The dialog needs a chosen technician and a reason before it sends anything")
    void dialogValidatesBeforeSending() {
        String dialog = body("async function openTeamReassignModal(");

        assertThat(dialog).contains("if (!chosen)").contains("if (!reason)");
        assertThat(dialog).contains("id=\"modal-team-reassign-reason\"").contains("required");
        assertThat(dialog.indexOf("if (!reason)")).isLessThan(dialog.indexOf("/reassign`"));
    }

    @Test
    @DisplayName("People who cannot take the ticket are shown but disabled, with the reason: current, inactive, offline, full")
    void blockedMembersAreDisabledWithAReason() {
        String blocker = body("function teamMemberBlocker(");

        assertThat(blocker).contains("'Current technician'").contains("'Inactive'").contains("'Offline'").contains("'No free slot'");
        assertThat(body("function teamMemberOptionHtml(")).contains("' disabled'").contains("team-reassign-blocker");
    }

    @Test
    @DisplayName("Only one dialog is opened at a time, and everything dynamic goes through escapeHtml")
    void oneAtATimeAndEscaped() {
        String dialog = body("async function openTeamReassignModal(");

        assertThat(dialog).contains("if (STATE.teamReassignOpening) return;").contains("STATE.teamReassignOpening = false;");
        assertThat(dialog).contains("escapeHtml(ticket.title)").contains("escapeHtml(ticket.id)").contains("escapeHtml(ticket.assigneeName");
        assertThat(body("function teamMemberOptionHtml(")).contains("escapeHtml(member.name)").contains("escapeHtml(member.id)");
    }

    @Test
    @DisplayName("After a move the open tab reloads, so the lead sees the new holder without pressing anything")
    void successReloadsTheActiveTab() {
        assertThat(body("async function openTeamReassignModal(")).contains("closeModal();").contains("refreshActiveTab();");
    }

    @Test
    @DisplayName("The buttons follow the server's reassignable flag and the dialog is opened from every place a lead sees a ticket")
    void buttonsFollowTheServerFlag() {
        assertThat(appJs).contains("canReassign: isTeamLead && t.reassignable === true");
        assertThat(appJs).contains("btn-tech-reassign").contains("btn-teamlead-assign").contains("btn-teamlead-reassign");
        Matcher opens = Pattern.compile("openTeamReassignModal\\(b\\.getAttribute\\('data-id'\\)\\)").matcher(appJs);
        int count = 0;
        while (opens.find()) {
            count++;
        }
        assertThat(count).as("technician card, unassigned list, escalation card").isEqualTo(3);
    }

    @Test
    @DisplayName("A ticket in triage shows a note instead of an Assign button")
    void triageTicketsShowANote() {
        String queues = body("function renderTeamLeadQueues(");

        assertThat(queues).contains("In triage: an administrator decides");
        assertThat(queues).contains("t.reassignable === true");
    }

    @Test
    @DisplayName("The dialog's styles exist")
    void stylesExist() {
        assertThat(stylesCss).contains(".team-reassign-list").contains(".team-reassign-option")
                .contains(".team-reassign-option.is-disabled").contains(".team-reassign-blocker");
    }

    // ---- Phase M4: dead and misleading buttons ---------------------------------------------------------------------

    @Test
    @DisplayName("M4: the dead 'Auto-Dispatch All' button and the failing claim button are gone, and the card says what happens instead")
    void deadButtonsAreRemoved() {
        assertThat(indexHtml).doesNotContain("btn-auto-dispatch").doesNotContain("Auto-Dispatch All");
        assertThat(appJs).doesNotContain("btn-teamlead-claim").doesNotContain("Acknowledge &amp; Handle");
        assertThat(indexHtml).contains("assigned automatically within about 30 seconds");
    }

    @Test
    @DisplayName("M4: every button the Team Lead tab draws has a click handler")
    void everyTeamLeadButtonHasAHandler() {
        String queues = body("function renderTeamLeadQueues(");
        Matcher classes = Pattern.compile("btn-teamlead-[a-z]+").matcher(queues);
        java.util.Set<String> buttonClasses = new java.util.TreeSet<>();
        while (classes.find()) {
            buttonClasses.add(classes.group());
        }

        assertThat(buttonClasses).containsExactlyInAnyOrder("btn-teamlead-assign", "btn-teamlead-reassign", "btn-teamlead-intervene");
        for (String cls : buttonClasses) {
            assertThat(queues).as("handler for " + cls).contains("querySelectorAll('." + cls + "')");
        }
    }

    @Test
    @DisplayName("M4: 'Inspect Escalation' opens the team queue at that ticket, with the mode buttons following and no filter hiding it")
    void inspectEscalationFocusesTheTicket() {
        String queues = body("function renderTeamLeadQueues(");

        assertThat(queues).contains("STATE.techFocusId = b.getAttribute('data-id');")
                .contains("setTechQueueMode('team');").contains("filter.value = 'ALL'");
        assertThat(queues).doesNotContain("STATE.techQueueMode = 'team'");
        assertThat(body("function setTechQueueMode(")).contains("btn-tech-mode-my").contains("btn-tech-mode-team");
        assertThat(body("function focusRequestedTicket(")).contains("scrollIntoView").contains("ticket-focus");
        assertThat(body("function renderTechnicianQueue(")).contains("focusRequestedTicket(container);");
        assertThat(body("function tpRowHtml(")).contains("data-ticket-card=");
        assertThat(stylesCss).contains(".ticket-focus");
    }

    @Test
    @DisplayName("M4: the mode buttons call the one helper, so the state and the buttons cannot disagree")
    void modeButtonsUseTheHelper() {
        assertThat(appJs).contains("setTechQueueMode('my');").contains("setTechQueueMode('team');");
        assertThat(appJs).doesNotContain("btnTechTeam.className").doesNotContain("btnTechMy.className");
    }

    @Test
    @DisplayName("M4: acting for a colleague is labelled as such instead of a bare 'Acknowledge'")
    void actingForAColleagueIsLabelled() {
        assertThat(body("function tpPermissions(")).contains("Acknowledge for ${escapeHtml(t.assigneeName || t.assigneeId)}");
        assertThat(body("function tpActionsHtml(")).contains("p.ackLabel");
    }
}
