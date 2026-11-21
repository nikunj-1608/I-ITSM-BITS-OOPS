package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H7: reclassifying a ticket changes its category and service, never its history. These tests pin the
 * domain contract of {@link Incident#reclassify}: a manual override, the recorded response, the paused
 * clock and a final SLA verdict all survive.
 */
class IncidentReclassifyTest {

    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-25T12:00:00Z");
    }

    private Incident classifiedAs(Priority priority) {
        Incident incident = new Incident("INC-1", "Valid Title", "Valid description that is long enough.", "EMP-1", now);
        incident.classify(IncidentCategory.APPLICATION, "SVC-1", decision(priority), SlaPolicy.defaultFor(priority), now);
        return incident;
    }

    private PriorityDecision decision(Priority priority) {
        return new PriorityDecision(priority, 0.40, Collections.emptyList(), Collections.emptyList(), now, null, null);
    }

    @Test
    @DisplayName("H7: reclassify adopts category, service and the new decision on an untouched ticket")
    void untouchedTicketTakesTheNewDecision() {
        Incident incident = classifiedAs(Priority.P4);

        incident.reclassify(IncidentCategory.SECURITY, "SVC-2", decision(Priority.P2), SlaPolicy.defaultFor(Priority.P2), now);

        assertThat(incident.getCategory()).isEqualTo(IncidentCategory.SECURITY);
        assertThat(incident.getServiceId()).isEqualTo("SVC-2");
        assertThat(incident.getPriority()).isEqualTo(Priority.P2);
        assertThat(incident.getSlaTracker().getResolutionDueAt())
                .isEqualTo(now.plus(SlaPolicy.defaultFor(Priority.P2).getResolutionTarget()));
    }

    @Test
    @DisplayName("H7: a manual priority override survives reclassify, with who and why")
    void overrideSurvivesReclassify() {
        Incident incident = classifiedAs(Priority.P4);
        incident.overridePriority(Priority.P1, "ADMIN-1", "Outage", SlaPolicy.defaultFor(Priority.P1), now);

        incident.reclassify(IncidentCategory.SECURITY, "SVC-2", decision(Priority.P3), SlaPolicy.defaultFor(Priority.P3), now);

        assertThat(incident.getPriority()).isEqualTo(Priority.P1);
        assertThat(incident.getPriorityDecision().isOverridden()).isTrue();
        assertThat(incident.getPriorityDecision().overriddenBy()).isEqualTo("ADMIN-1");
        assertThat(incident.getSlaTracker().getResolutionDueAt())
                .isEqualTo(now.plus(SlaPolicy.defaultFor(Priority.P1).getResolutionTarget()));
    }

    @Test
    @DisplayName("H7: the recorded response time survives reclassify")
    void respondedAtSurvives() {
        Incident incident = classifiedAs(Priority.P3);
        Instant responded = now.plus(Duration.ofMinutes(3));
        incident.getSlaTracker().recordResponse(responded);

        incident.reclassify(IncidentCategory.SECURITY, "SVC-2", decision(Priority.P3), SlaPolicy.defaultFor(Priority.P3),
                now.plus(Duration.ofMinutes(10)));

        assertThat(incident.getSlaTracker().getRespondedAt()).isEqualTo(responded);
    }

    @Test
    @DisplayName("H7: a paused SLA clock stays paused, and time already paused is kept")
    void pausedClockSurvives() {
        Incident incident = classifiedAs(Priority.P3);
        SlaTracker tracker = incident.getSlaTracker();
        tracker.pauseClock(now.plus(Duration.ofMinutes(5)));
        tracker.resumeClock(now.plus(Duration.ofMinutes(15)));
        Instant secondPause = now.plus(Duration.ofMinutes(20));
        tracker.pauseClock(secondPause);

        incident.reclassify(IncidentCategory.SECURITY, "SVC-2", decision(Priority.P3), SlaPolicy.defaultFor(Priority.P3),
                now.plus(Duration.ofMinutes(25)));

        SlaTracker after = incident.getSlaTracker();
        assertThat(after.isPaused()).isTrue();
        assertThat(after.getPausedAt()).isEqualTo(secondPause);
        assertThat(after.getPausedDuration()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("H7: a resolved ticket keeps its priority, deadlines, resolvedAt and MET verdict")
    void resolvedTicketKeepsItsVerdict() {
        Incident incident = classifiedAs(Priority.P4);
        SlaTracker tracker = incident.getSlaTracker();
        Instant due = tracker.getResolutionDueAt();
        Instant resolved = now.plus(Duration.ofMinutes(10));
        tracker.recordResolution(resolved);

        incident.reclassify(IncidentCategory.SECURITY, "SVC-2", decision(Priority.P1), SlaPolicy.defaultFor(Priority.P1),
                now.plus(Duration.ofHours(3)));

        assertThat(incident.getCategory()).isEqualTo(IncidentCategory.SECURITY);
        SlaTracker after = incident.getSlaTracker();
        assertThat(incident.getPriority()).isEqualTo(Priority.P4);
        assertThat(after.getResolvedAt()).isEqualTo(resolved);
        assertThat(after.getResolutionDueAt()).isEqualTo(due);
        assertThat(after.getStatus()).isEqualTo(SlaStatus.MET);
    }
}
