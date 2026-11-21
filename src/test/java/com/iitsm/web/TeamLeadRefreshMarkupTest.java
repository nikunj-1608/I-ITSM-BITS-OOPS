package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structure of the Team Lead tab's refresh behaviour (Phase M2). There is no JavaScript test runner, so the source is
 * pinned as text; the behaviour itself is checked in a real browser by {@code scripts/live-checks/team_lead_refresh_browser.py}.
 */
class TeamLeadRefreshMarkupTest {

    private static String indexHtml;
    private static String appJs;

    @BeforeAll
    static void load() throws IOException {
        indexHtml = read("/static/index.html");
        appJs = read("/static/app.js");
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = TeamLeadRefreshMarkupTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("classpath resource " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The source from the given marker to the next top-level function or section marker. */
    private static String body(String marker) {
        int start = appJs.indexOf(marker);
        assertThat(start).as(marker).isGreaterThanOrEqualTo(0);
        int next = appJs.indexOf("\nfunction ", start + marker.length());
        int nextAsync = appJs.indexOf("\nasync function ", start + marker.length());
        int end = java.util.stream.IntStream.of(next, nextAsync).filter(i -> i > 0).min().orElse(appJs.length());
        return appJs.substring(start, end);
    }

    @Test
    @DisplayName("The tab reloads on a ten second timer that starts and stops with the other background timers")
    void timerIsStartedAndStopped() {
        assertThat(appJs).contains("const TEAM_LEAD_REFRESH_MS = 10000;");
        assertThat(body("function startBackgroundPolling(")).contains("STATE.teamLeadTimer = setInterval(autoRefreshTeamLead, TEAM_LEAD_REFRESH_MS)");
        assertThat(body("function stopBackgroundPolling(")).contains("clearInterval(STATE.teamLeadTimer)");
    }

    @Test
    @DisplayName("The timer reloads only an idle, visible Team Lead tab: no dialog, no write, no load already running")
    void timerHasEveryGuard() {
        String timer = body("function autoRefreshTeamLead(");

        assertThat(timer).contains("STATE.activeTab !== 'console-teamlead'");
        assertThat(timer).contains("document.hidden").contains("openDialogElement()");
        assertThat(timer).contains("pendingWrites > 0").contains("STATE.teamLead.inFlight > 0");
        assertThat(appJs).contains("document.addEventListener('visibilitychange'");
    }

    @Test
    @DisplayName("Every write counts itself in and out, so the timer never reloads under a running write")
    void writesAreCounted() {
        String api = body("async function apiCall(");

        assertThat(appJs).contains("let pendingWrites = 0;");
        assertThat(api).contains("if (isWrite) pendingWrites++;").contains("if (isWrite) pendingWrites--;");
    }

    @Test
    @DisplayName("Loading text appears only before the first real content, so a reload never blanks the panel")
    void loadingTextOnlyBeforeFirstContent() {
        String load = body("async function loadTeamLeadView(");

        assertThat(load).contains("if (!lead.hasRoster && grid)");
        assertThat(load).contains("if (!lead.hasQueues && unassigned)").contains("if (!lead.hasQueues && escalation)");
        assertThat(load).contains("lead.hasRoster = true;").contains("lead.hasQueues = true;");
    }

    @Test
    @DisplayName("The roster and the queue load independently, each with its own error, so one failure hides nothing")
    void callsAreIndependent() {
        String load = body("async function loadTeamLeadView(");

        assertThat(load).contains("Promise.allSettled([apiCall('/api/admin/technicians'), apiCall('/api/technician/team-queue')])");
        assertThat(load).doesNotContain("await apiCall('/api/admin/technicians')");
        assertThat(load).contains("rosterResult.status === 'fulfilled'").contains("queueResult.status === 'fulfilled'");
        assertThat(load).contains("if (!lead.hasRoster && grid)").contains("teamLeadErrorHtml(");
    }

    @Test
    @DisplayName("A late answer, or one that arrives after sign-out, is discarded")
    void lateAnswersAreDiscarded() {
        String load = body("async function loadTeamLeadView(");

        assertThat(load).contains("const seq = ++lead.requestSeq;");
        assertThat(load).contains("if (seq !== lead.requestSeq || !STATE.currentUser) return;");
        assertThat(body("function handleSignOut(")).contains("resetTeamLeadView();");
        assertThat(body("function resetTeamLeadView(")).contains("STATE.teamLead.requestSeq++")
                .contains("STATE.teamLead.hasRoster = false").contains("STATE.teamLead.hasQueues = false");
    }

    @Test
    @DisplayName("A failed update says so and keeps the last data; a good one shows when it happened")
    void updatedStampTellsTheTruth() {
        String load = body("async function loadTeamLeadView(");

        assertThat(load).contains("Update failed at ${time}; showing the last data received").contains("`Updated ${time}`");
        assertThat(indexHtml).contains("id=\"teamlead-updated\"").contains("id=\"btn-refresh-teamlead\"");
    }

    @Test
    @DisplayName("The Refresh button calls the loader without passing it the click event")
    void refreshButtonDoesNotPassTheEvent() {
        assertThat(appJs).contains("refTeam.addEventListener('click', () => loadTeamLeadView());");
        assertThat(appJs).doesNotContain("refTeam.addEventListener('click', loadTeamLeadView)");
    }
}
