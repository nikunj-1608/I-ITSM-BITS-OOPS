package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structure of the Team Lead tab (Phase M1). There is no JavaScript test runner, so the source is pinned as text: the
 * roster reads the field names the API really sends, the escalation list looks at open tickets only, and no label
 * promises a rule the code does not apply.
 */
class TeamLeadPanelMarkupTest {

    private static String indexHtml;
    private static String appJs;

    @BeforeAll
    static void load() throws IOException {
        indexHtml = read("/static/index.html");
        appJs = read("/static/app.js");
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = TeamLeadPanelMarkupTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("classpath resource " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String section(String startMarker, String endMarker) {
        int start = appJs.indexOf(startMarker);
        assertThat(start).as(startMarker).isGreaterThan(0);
        int end = appJs.indexOf(endMarker, start);
        assertThat(end).as(endMarker).isGreaterThan(start);
        return appJs.substring(start, end);
    }

    @Test
    @DisplayName("The roster card reads the load field the API sends, not a misspelt one")
    void rosterReadsTheRealLoadField() {
        String roster = section("function renderTeamRoster(", "// 9. Console 4");

        assertThat(roster).contains("tech.activeIncidentCount").doesNotContain("activeIncidentsCount");
    }

    @Test
    @DisplayName("The roster card shows skills and role, not a domain and tier the API never sends")
    void rosterShowsOnlyRealFields() {
        String roster = section("function renderTeamRoster(", "// 9. Console 4");

        assertThat(roster).contains("tech.skills").contains("tech.role");
        assertThat(roster).doesNotContain("tech.domain").doesNotContain("tech.designation");
        assertThat(roster).doesNotContain("'Engineering'").doesNotContain("'L2'");
    }

    @Test
    @DisplayName("The unassigned and escalation lists look at open tickets only")
    void listsIgnoreFinishedTickets() {
        String queues = section("function renderTeamLeadQueues(", "function renderTeamRoster(");

        assertThat(appJs).contains("const FINISHED_TICKET_STATUSES = ['RESOLVED', 'VERIFIED', 'CLOSED'];");
        assertThat(queues).contains("teamQueue.filter(isOpenTicket)");
        assertThat(queues).contains("open.filter(t => !t.assigneeId)");
        assertThat(queues).contains("open.filter(t => ESCALATION_SLA_STATUSES.includes(t.slaStatus))");
    }

    @Test
    @DisplayName("A priority alone no longer puts a ticket in the escalation list, and a missing priority is not shown as P1")
    void noP1ShortcutAndNoFakePriority() {
        String queues = section("function renderTeamLeadQueues(", "function renderTeamRoster(");

        assertThat(queues).doesNotContain("t.priority === 'P1'").doesNotContain("t.priority || 'P1'");
        assertThat(queues).doesNotContain("Zero active SLA breaches or P1 escalations");
    }

    @Test
    @DisplayName("The escalation card is titled for what it lists, with no unused 90 percent threshold or tier label")
    void escalationCardTitleMatchesItsRule() {
        assertThat(indexHtml).contains("SLA Escalation Monitor (At Risk or Breached)");
        assertThat(indexHtml).doesNotContain("Tier 2 SLA Escalation Monitor").doesNotContain("&gt; 90% Threshold");
    }
}
