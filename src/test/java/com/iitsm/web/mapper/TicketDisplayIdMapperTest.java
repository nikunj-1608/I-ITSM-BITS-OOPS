package com.iitsm.web.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iitsm.application.dto.AdminIncidentDetail;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.web.dto.AdminIncidentView;
import com.iitsm.web.dto.EmployeeIncidentView;
import com.iitsm.web.dto.TechnicianIncidentView;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The staff display name (priority + ticket id) appears in staff views only, never for employees. */
class TicketDisplayIdMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final String ID = "FIN-2026-000142";
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    private static Incident newIncident() {
        return new Incident(ID, "Payment gateway timeouts", "Card payments are timing out for all customers.",
                "EMP-01", NOW);
    }

    private static Incident classified(Priority priority) {
        Incident incident = newIncident();
        PriorityDecision decision = new PriorityDecision(priority, 0.65, List.of(), List.of(), NOW, null, null);
        SlaPolicy policy = new SlaPolicy("SLA", priority, null, Duration.ofMinutes(15), Duration.ofHours(2), 0.75);
        incident.classify(IncidentCategory.APPLICATION, "SVC-PAYMENT", decision, policy, NOW);
        return incident;
    }

    @Test
    @DisplayName("technician and admin views show priority-first display name next to the plain id")
    void staffViewsCarryTheDisplayName() {
        Incident incident = classified(Priority.P2);

        TechnicianIncidentView technician = IncidentDtoMapper.toTechnicianView(incident, null);
        AdminIncidentView admin = IncidentDtoMapper.toAdminView(incident, null, null, List.of());

        assertThat(technician.id()).isEqualTo(ID);
        assertThat(technician.displayId()).isEqualTo("P2-FIN-2026-000142");
        assertThat(admin.id()).isEqualTo(ID);
        assertThat(admin.displayId()).isEqualTo("P2-FIN-2026-000142");
    }

    @Test
    @DisplayName("the display name follows a priority override while the id stays the same")
    void overrideChangesTheDisplayNameButNotTheId() {
        Incident incident = classified(Priority.P3);
        assertThat(IncidentDtoMapper.toAdminView(incident, null, null, List.of()).displayId())
                .isEqualTo("P3-FIN-2026-000142");

        incident.overridePriority(Priority.P1, "ADMIN-01", "Board escalation", NOW.plusSeconds(60));

        AdminIncidentView after = IncidentDtoMapper.toAdminView(incident, null, null, List.of());
        assertThat(after.id()).isEqualTo(ID);
        assertThat(after.displayId()).isEqualTo("P1-FIN-2026-000142");
        assertThat(IncidentDtoMapper.toTechnicianView(incident, null).displayId()).isEqualTo("P1-FIN-2026-000142");
    }

    @Test
    @DisplayName("a ticket that has no priority yet is shown by its plain id")
    void unclassifiedTicketShowsPlainId() {
        Incident incident = newIncident();

        assertThat(IncidentDtoMapper.toTechnicianView(incident, null).displayId()).isEqualTo(ID);
        assertThat(IncidentDtoMapper.toAdminView(incident, null, null, List.of()).displayId()).isEqualTo(ID);
    }

    @Test
    @DisplayName("the admin detail view exposes the display name at the top level of its JSON")
    void adminDetailJsonHasDisplayId() throws Exception {
        AdminIncidentDetail detail = new AdminIncidentDetail(classified(Priority.P1), null, null, null, null, List.of());

        String body = json.writeValueAsString(IncidentDtoMapper.toAdminDetailView(detail));

        assertThat(json.readTree(body).get("displayId").asText()).isEqualTo("P1-FIN-2026-000142");
        assertThat(json.readTree(body).get("id").asText()).isEqualTo(ID);
    }

    @Test
    @DisplayName("employee view cannot carry a priority or display name: by type and in its JSON")
    void employeeViewNeverHasPriority() throws Exception {
        List<String> fields = Arrays.stream(EmployeeIncidentView.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(fields).doesNotContain("displayId", "priority", "priorityDecision");

        EmployeeIncidentView view = IncidentDtoMapper.toEmployeeView(classified(Priority.P1), null, List.of());
        String body = json.writeValueAsString(view);

        assertThat(view.id()).isEqualTo(ID);
        assertThat(body).doesNotContain("displayId").doesNotContain("P1-FIN").doesNotContain("priority")
                .doesNotContain("\"P1\"");
    }
}
