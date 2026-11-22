package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.InvalidStateTransitionException;
import com.iitsm.domain.exception.ReassignmentRequestNotPendingException;
import com.iitsm.domain.exception.ValidationException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** ADR-028 and ADR-029: which statuses accept a priority override, and a decided reassignment request stays decided. */
class IncidentOverrideStatusRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Resolution RESOLUTION = new Resolution("cause", "steps", "prevention", "NETWORK", "TECH-1", NOW);

    /** A ticket driven through the real transitions into the given status. */
    private static Incident ticketIn(IncidentStatus target) {
        Incident incident = new Incident("INC-RULE", "VPN keeps dropping", "The VPN client drops every hour for all of us.", "EMP-1", NOW);
        if (target == IncidentStatus.NEW) {
            return incident;
        }
        incident.classify(IncidentCategory.NETWORK, "SVC-VPN",
                new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null), SlaPolicy.defaultFor(Priority.P3), NOW);
        if (target == IncidentStatus.CLASSIFIED) {
            return incident;
        }
        incident.assign("TECH-1", NOW);
        if (target == IncidentStatus.ASSIGNED) {
            return incident;
        }
        incident.acknowledge("TECH-1", NOW);
        if (target == IncidentStatus.ACKNOWLEDGED) {
            return incident;
        }
        incident.startInvestigation("TECH-1", NOW);
        if (target == IncidentStatus.IN_PROGRESS) {
            return incident;
        }
        if (target == IncidentStatus.PENDING_INFO) {
            incident.requestInfo("TECH-1", "Which office?", NOW);
            return incident;
        }
        incident.resolve(RESOLUTION, NOW);
        if (target == IncidentStatus.RESOLVED) {
            return incident;
        }
        incident.verify("EMP-1", NOW);
        if (target == IncidentStatus.VERIFIED) {
            return incident;
        }
        incident.close("EMP-1", null, NOW);
        return incident;
    }

    @ParameterizedTest(name = "[{index}] an open ticket in {0} accepts a priority override")
    @EnumSource(value = IncidentStatus.class, names = {"CLASSIFIED", "ASSIGNED", "ACKNOWLEDGED", "IN_PROGRESS", "PENDING_INFO"})
    void openTicketAcceptsAnOverride(IncidentStatus status) {
        Incident incident = ticketIn(status);

        incident.overridePriority(Priority.P1, "ADMIN-1", "customer escalation", SlaPolicy.defaultFor(Priority.P1), NOW);

        assertThat(incident.getPriorityDecision().priority()).isEqualTo(Priority.P1);
        assertThat(incident.getPriorityDecision().isOverridden()).isTrue();
    }

    @ParameterizedTest(name = "[{index}] a ticket in {0} refuses a priority override and stays unchanged")
    @EnumSource(value = IncidentStatus.class, names = {"RESOLVED", "VERIFIED", "CLOSED"})
    void finishedTicketRefusesAnOverride(IncidentStatus status) {
        Incident incident = ticketIn(status);
        PriorityDecision decision = incident.getPriorityDecision();
        Instant due = incident.getSlaTracker().getResolutionDueAt();
        SlaStatus verdict = incident.getSlaTracker().getStatus();

        assertThatThrownBy(() -> incident.overridePriority(Priority.P1, "ADMIN-1", "too late", SlaPolicy.defaultFor(Priority.P1), NOW))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("overridePriority").hasMessageContaining(status.name());

        assertThat(incident.getPriorityDecision()).isSameAs(decision);
        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(due);
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(verdict);
    }

    @Test
    @DisplayName("A ticket that was never classified still reports the missing classification, not a state error")
    void unclassifiedTicketReportsTheMissingClassification() {
        assertThatThrownBy(() -> ticketIn(IncidentStatus.NEW)
                .overridePriority(Priority.P1, "ADMIN-1", "reason", null, NOW))
                .isInstanceOf(ValidationException.class).hasMessageContaining("classification");
    }

    @Test
    @DisplayName("ADR-029: a request that was approved or rejected cannot be decided again")
    void decidedRequestStaysDecided() {
        ReassignmentRequest approved = new ReassignmentRequest("R-1", "INC-1", "TECH-1", "wrong team", NOW);
        approved.approve("ADMIN-1", NOW);
        ReassignmentRequest rejected = new ReassignmentRequest("R-2", "INC-1", "TECH-1", "wrong team", NOW);
        rejected.reject("ADMIN-1", NOW);

        assertThat(approved.isPending()).isFalse();
        assertThatThrownBy(() -> approved.reject("ADMIN-2", NOW)).isInstanceOf(ReassignmentRequestNotPendingException.class);
        assertThatThrownBy(() -> rejected.approve("ADMIN-2", NOW)).isInstanceOf(ReassignmentRequestNotPendingException.class)
                .hasMessageContaining("R-2").hasMessageContaining("REJECTED");
        assertThat(rejected.getReviewedByAdminId()).as("the first decision stands").isEqualTo("ADMIN-1");
    }

    @Test
    @DisplayName("ADR-029: a technician can be allowed to work on the ticket again")
    void excludedTechnicianCanBeRemoved() {
        Incident incident = ticketIn(IncidentStatus.CLASSIFIED);
        incident.addExcludedTechnician("TECH-1");
        incident.addExcludedTechnician("TECH-2");

        incident.removeExcludedTechnician("TECH-1");
        incident.removeExcludedTechnician(null);
        incident.removeExcludedTechnician("TECH-NEVER");

        assertThat(incident.getExcludedTechnicianIds()).containsExactly("TECH-2");
    }
}
