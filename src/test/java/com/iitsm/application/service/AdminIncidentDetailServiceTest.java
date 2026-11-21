package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iitsm.application.dto.AdminIncidentDetail;
import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.BusinessCriticality;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminIncidentDetailServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final String ADMIN = "ADMIN-SYS-01";

    private IncidentLifecycleService lifecycleService;
    private UserRepository userRepository;
    private ItServiceRepository itServiceRepository;
    private SupportTeamRepository supportTeamRepository;
    private AuditService auditService;
    private AdminIncidentDetailService service;

    @BeforeEach
    void setUp() {
        lifecycleService = mock(IncidentLifecycleService.class);
        userRepository = mock(UserRepository.class);
        itServiceRepository = mock(ItServiceRepository.class);
        supportTeamRepository = mock(SupportTeamRepository.class);
        auditService = mock(AuditService.class);
        service = new AdminIncidentDetailService(
                lifecycleService, userRepository, itServiceRepository, supportTeamRepository, auditService);
        when(userRepository.findById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(itServiceRepository.findById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(supportTeamRepository.findById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
    }

    private Incident incident(boolean assigned) {
        Incident incident = new Incident("INC-1", "Payment gateway timeouts", "Card payments are timing out for everyone.", "EMP-1", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P2, 0.6, List.of(), List.of(), NOW, null, null);
        SlaPolicy policy = new SlaPolicy("SLA-P2", Priority.P2, null, Duration.ofMinutes(15), Duration.ofHours(2), 0.75);
        incident.classify(IncidentCategory.APPLICATION, "SVC-PAYMENT", decision, policy, NOW);
        if (assigned) {
            incident.assign("TECH-1", NOW);
        }
        when(lifecycleService.getIncident(ADMIN, "INC-1")).thenReturn(incident);
        return incident;
    }

    private static Technician technician(String teamId) {
        return new Technician("TECH-1", "Tech One", new Email("t1@test.com"), "hash", Set.of(), 5, 5, teamId);
    }

    private static SupportTeam team(String id, String name) {
        return new SupportTeam(id, name, Set.of(IncidentCategory.APPLICATION), "LEAD-" + id);
    }

    private static ItService itService(String owningTeamId) {
        return new ItService("SVC-PAYMENT", "Payment Processing", owningTeamId,
                BusinessCriticality.TIER_4_MISSION_CRITICAL, 100, Set.of("payment"));
    }

    @Test
    @DisplayName("Assigned incident reports the assignee's own support team")
    void usesAssigneeTeamWhenAssigned() {
        incident(true);
        when(userRepository.findById("TECH-1")).thenReturn(Optional.of(technician("TEAM-NET")));
        when(itServiceRepository.findById("SVC-PAYMENT")).thenReturn(Optional.of(itService("TEAM-APP")));
        when(supportTeamRepository.findById("TEAM-NET")).thenReturn(Optional.of(team("TEAM-NET", "Network")));

        AdminIncidentDetail detail = service.getDetail(ADMIN, "INC-1");

        assertThat(detail.supportTeam().getName()).isEqualTo("Network");
        assertThat(detail.assignee().getId()).isEqualTo("TECH-1");
    }

    @Test
    @DisplayName("Unassigned incident falls back to the IT service's owning team")
    void fallsBackToOwningTeamWhenUnassigned() {
        incident(false);
        when(itServiceRepository.findById("SVC-PAYMENT")).thenReturn(Optional.of(itService("TEAM-APP")));
        when(supportTeamRepository.findById("TEAM-APP")).thenReturn(Optional.of(team("TEAM-APP", "Application")));

        AdminIncidentDetail detail = service.getDetail(ADMIN, "INC-1");

        assertThat(detail.assignee()).isNull();
        assertThat(detail.supportTeam().getName()).isEqualTo("Application");
        assertThat(detail.service().getName()).isEqualTo("Payment Processing");
    }

    @Test
    @DisplayName("Assignee without a team falls back to the owning team")
    void assigneeWithoutTeamFallsBack() {
        incident(true);
        when(userRepository.findById("TECH-1")).thenReturn(Optional.of(technician(null)));
        when(itServiceRepository.findById("SVC-PAYMENT")).thenReturn(Optional.of(itService("TEAM-APP")));
        when(supportTeamRepository.findById("TEAM-APP")).thenReturn(Optional.of(team("TEAM-APP", "Application")));

        assertThat(service.getDetail(ADMIN, "INC-1").supportTeam().getId()).isEqualTo("TEAM-APP");
    }

    @Test
    @DisplayName("Dangling references (deleted service, user or team) yield nulls, not failures")
    void danglingReferencesYieldNulls() {
        incident(true);

        AdminIncidentDetail detail = service.getDetail(ADMIN, "INC-1");

        assertThat(detail.reporter()).isNull();
        assertThat(detail.assignee()).isNull();
        assertThat(detail.service()).isNull();
        assertThat(detail.supportTeam()).isNull();
    }

    @Test
    @DisplayName("Includes the audit trail from AuditService")
    void includesAuditTrail() {
        incident(false);
        AuditRecord record = new AuditRecord("A1", NOW, "SYSTEM", "IncidentClassified", "INC-1", "NEW", "CLASSIFIED", "x");
        when(auditService.getAuditTrailForIncident("INC-1")).thenReturn(List.of(record));

        assertThat(service.getDetail(ADMIN, "INC-1").auditRecords()).containsExactly(record);
    }
}
