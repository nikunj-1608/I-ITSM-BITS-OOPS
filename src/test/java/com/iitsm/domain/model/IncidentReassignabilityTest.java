package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The rule behind the team lead's "Assign / Reassign" (ADR-045): who, which status, and the triage exception. */
class IncidentReassignabilityTest {

    private static final Set<String> NONE = Set.of();

    private static TeamLead lead() {
        return new TeamLead("LEAD-X", "Lead X", new Email("lead.x@nimbusfinserve.com"), "hash",
                Set.of(new Skill("Routing", IncidentCategory.NETWORK)), 8, 6, "TEAM-X");
    }

    private static Technician technician() {
        return new Technician("TECH-X", "Tech X", new Email("tech.x@nimbusfinserve.com"), "hash",
                UserRole.ROLE_TECHNICIAN, Set.of(new Skill("Routing", IncidentCategory.NETWORK)), 4, 5, "TEAM-X");
    }

    private static Incident incidentIn(IncidentStatus status, boolean needsTriage) {
        Incident incident = new Incident("INC-RE", "VPN keeps dropping", "The VPN client drops every hour for all of us.",
                "EMP-1", Instant.parse("2026-11-19T10:00:00Z"));
        incident.transitionTo(status);
        incident.setNeedsTriage(needsTriage);
        return incident;
    }

    @Test
    @DisplayName("Exactly CLASSIFIED, ASSIGNED, ACKNOWLEDGED and IN_PROGRESS may be reassigned by a team lead")
    void statusTable() {
        Set<IncidentStatus> allowed = Stream.of(IncidentStatus.values())
                .filter(IncidentStatus::canBeReassignedByTeamLead).collect(Collectors.toSet());

        assertThat(allowed).containsExactlyInAnyOrder(IncidentStatus.CLASSIFIED, IncidentStatus.ASSIGNED,
                IncidentStatus.ACKNOWLEDGED, IncidentStatus.IN_PROGRESS);
    }

    @ParameterizedTest
    @EnumSource(IncidentStatus.class)
    @DisplayName("A team lead may reassign an open ticket that is not in triage, whatever the status says")
    void leadFollowsTheStatusTable(IncidentStatus status) {
        assertThat(incidentIn(status, false).canBeReassignedBy(lead())).isEqualTo(status.canBeReassignedByTeamLead());
    }

    @ParameterizedTest
    @EnumSource(IncidentStatus.class)
    @DisplayName("A ticket in triage is never reassignable by a lead")
    void triageBlocksEveryStatus(IncidentStatus status) {
        assertThat(incidentIn(status, true).canBeReassignedBy(lead())).isFalse();
    }

    @Test
    @DisplayName("A plain technician, an employee, an administrator and nobody may not")
    void onlyALeadMay() {
        Incident incident = incidentIn(IncidentStatus.ASSIGNED, false);

        assertThat(incident.canBeReassignedBy(technician())).isFalse();
        assertThat(incident.canBeReassignedBy(null)).isFalse();
        assertThat(technician().canReassignTeamTickets()).isFalse();
        assertThat(lead().canReassignTeamTickets()).isTrue();
        assertThat(NONE).isEmpty();
    }
}
