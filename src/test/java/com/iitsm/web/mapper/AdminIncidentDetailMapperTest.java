package com.iitsm.web.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.dto.AdminIncidentDetail;
import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.BusinessCriticality;
import com.iitsm.domain.model.Designation;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Employee;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.web.dto.AdminIncidentDetailView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminIncidentDetailMapperTest {

    private static final Instant CREATED = Instant.parse("2026-10-30T10:00:00Z");

    private static Incident classifiedIncident() {
        Incident incident = new Incident(
                "INC-DET-01", "Payment gateway timeouts", "Card payments are timing out for all customers.", "EMP-01", CREATED);
        PriorityDecision decision = new PriorityDecision(Priority.P2, 0.65, List.of(), List.of(), CREATED, null, null);
        SlaPolicy policy = new SlaPolicy("SLA-P2", Priority.P2, null, Duration.ofMinutes(15), Duration.ofHours(2), 0.75);
        incident.classify(IncidentCategory.APPLICATION, "SVC-PAYMENT", decision, policy, CREATED);
        return incident;
    }

    private static AuditRecord audit(String id, Instant at) {
        return new AuditRecord(id, at, "SYSTEM", "IncidentClassified", "INC-DET-01", "NEW", "CLASSIFIED", "Classified");
    }

    @Test
    @DisplayName("Maps reporter, service, support team, public status, triage flag and last-modified")
    void mapsAllRelatedDetails() {
        Employee reporter = new Employee(
                "EMP-01", "Alice Reporter", new Email("alice@nimbus.com"), "hash", Designation.MANAGER, "Finance", 5);
        ItService service = new ItService(
                "SVC-PAYMENT", "Payment Processing", "TEAM-APP", BusinessCriticality.TIER_4_MISSION_CRITICAL, 500, Set.of("payment"));
        SupportTeam team = new SupportTeam("TEAM-APP", "Application Support", Set.of(IncidentCategory.APPLICATION), "LEAD-APP");
        Instant later = CREATED.plusSeconds(600);
        AdminIncidentDetail detail = new AdminIncidentDetail(
                classifiedIncident(), reporter, null, service, team, List.of(audit("A1", CREATED), audit("A2", later)));

        AdminIncidentDetailView view = IncidentDtoMapper.toAdminDetailView(detail);

        assertThat(view.incident().id()).isEqualTo("INC-DET-01");
        assertThat(view.incident().auditTrail()).hasSize(2);
        assertThat(view.reporter().email()).isEqualTo("alice@nimbus.com");
        assertThat(view.reporter().department()).isEqualTo("Finance");
        assertThat(view.reporter().designation()).isEqualTo("Manager");
        assertThat(view.service().name()).isEqualTo("Payment Processing");
        assertThat(view.service().criticalityTier()).isEqualTo("TIER_4_MISSION_CRITICAL");
        assertThat(view.service().criticalityLabel()).isEqualTo("Mission Critical");
        assertThat(view.supportTeam().name()).isEqualTo("Application Support");
        assertThat(view.publicStatus()).isEqualTo("New");
        assertThat(view.needsTriage()).isFalse();
        assertThat(view.lastModifiedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("Missing related entities map to null instead of failing")
    void missingRelatedEntitiesMapToNull() {
        AdminIncidentDetail detail = new AdminIncidentDetail(classifiedIncident(), null, null, null, null, null);

        AdminIncidentDetailView view = IncidentDtoMapper.toAdminDetailView(detail);

        assertThat(view.reporter()).isNull();
        assertThat(view.service()).isNull();
        assertThat(view.supportTeam()).isNull();
        assertThat(view.incident().auditTrail()).isEmpty();
        assertThat(view.lastModifiedAt()).as("falls back to creation time").isEqualTo(CREATED);
    }

    @Test
    @DisplayName("Triage flag is exposed")
    void exposesTriageFlag() {
        Incident incident = classifiedIncident();
        incident.setNeedsTriage(true);

        AdminIncidentDetailView view = IncidentDtoMapper.toAdminDetailView(
                new AdminIncidentDetail(incident, null, null, null, null, List.of()));

        assertThat(view.needsTriage()).isTrue();
    }
}
