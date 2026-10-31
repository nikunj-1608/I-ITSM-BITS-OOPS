package com.iitsm.web.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.Incident;
import com.iitsm.web.dto.EmployeeIncidentView;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Employees must never learn a ticket's priority or SLA state. The ticket's activity history is built
 * from audit records, some of which quote the priority, so those entries are filtered out of the
 * employee view (by an allow-list, so new event types are hidden until someone decides otherwise).
 */
class EmployeeAuditTrailPrivacyTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final String ID = "ENG-2026-000001";

    /** Event types that carry the priority, SLA state or escalation details of a ticket. */
    private static final Set<String> HIDDEN_FROM_EMPLOYEES = Set.of(
            "PriorityCalculated", "PriorityOverridden", "SlaWarning", "SlaViolated", "IncidentEscalated");

    private static AuditRecord audit(String action, String description) {
        return new AuditRecord("AUD-" + action, NOW, "SYSTEM", action, ID, null, null, description);
    }

    private static Incident incident() {
        return new Incident(ID, "VPN keeps dropping", "The VPN drops every few minutes for me today.", "EMP-01", NOW);
    }

    @Test
    @DisplayName("priority, SLA and escalation entries are removed from an employee's activity history")
    void priorityAndSlaEntriesAreHidden() {
        List<AuditRecord> trail = List.of(
                audit("IncidentCreated", "Incident raised: VPN keeps dropping"),
                audit("PriorityCalculated", "Calculated priority P3 (Score: 0.3975)"),
                audit("PriorityOverridden", "Priority changed from P3 to P1. Reason: Board escalation"),
                audit("SlaWarning", "SLA Warning: 80% of the resolution target used"),
                audit("SlaViolated", "SLA Violated for target RESOLUTION at 2026-10-31T12:00:00Z"),
                audit("IncidentEscalated", "Incident escalated from TECHNICIAN to TEAM_LEAD (TL-1): SLA breach"),
                audit("IncidentAssigned", "Assigned to technician TECH-NET-1"));

        EmployeeIncidentView view = IncidentDtoMapper.toEmployeeView(incident(), "David", trail);

        assertThat(view.auditTrail()).extracting(EmployeeIncidentView.EmployeeAuditRecordView::action)
                .containsExactly("IncidentCreated", "IncidentAssigned");
    }

    @Test
    @DisplayName("the employee JSON never mentions priority, SLA or escalation, whatever the audit trail holds")
    void employeeJsonHasNoPriorityText() throws Exception {
        List<AuditRecord> trail = List.of(
                audit("IncidentCreated", "Incident raised: VPN keeps dropping"),
                audit("PriorityCalculated", "Calculated priority P3 (Score: 0.3975)"),
                audit("PriorityOverridden", "Priority changed from P3 to P1. Reason: Board escalation"),
                audit("SlaViolated", "SLA Violated for target RESOLUTION"),
                audit("IncidentClassified", "Classified under category NETWORK (Service: SVC-VPN)"));

        String body = new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(IncidentDtoMapper.toEmployeeView(incident(), "David", trail));

        assertThat(body.toLowerCase()).doesNotContain("priority").doesNotContain("score").doesNotContain("sla")
                .doesNotContain("escalat");
        assertThat(body).contains("Classified under category NETWORK");
    }

    @Test
    @DisplayName("every event type is either shown to employees or deliberately hidden (new events need a decision)")
    void everyEventTypeIsClassified() {
        Set<String> allEvents = Arrays.stream(DomainEvent.class.getPermittedSubclasses())
                .map(Class::getSimpleName).collect(Collectors.toSet());
        List<AuditRecord> everything = allEvents.stream().map(name -> audit(name, "x")).toList();

        Set<String> shown = IncidentDtoMapper.toEmployeeView(incident(), null, everything).auditTrail().stream()
                .map(EmployeeIncidentView.EmployeeAuditRecordView::action).collect(Collectors.toSet());

        Set<String> hidden = allEvents.stream().filter(name -> !shown.contains(name)).collect(Collectors.toSet());
        assertThat(hidden).isEqualTo(HIDDEN_FROM_EMPLOYEES);
    }

    @Test
    @DisplayName("an unknown audit action is hidden from employees by default")
    void unknownActionsAreHidden() {
        EmployeeIncidentView view = IncidentDtoMapper.toEmployeeView(incident(), null,
                List.of(audit("SomethingNewAndInternal", "Priority is P1")));

        assertThat(view.auditTrail()).isEmpty();
    }

    @Test
    @DisplayName("a missing audit trail still gives an empty history")
    void nullTrailIsEmpty() {
        assertThat(IncidentDtoMapper.toEmployeeView(incident(), null, null).auditTrail()).isEmpty();
    }
}
