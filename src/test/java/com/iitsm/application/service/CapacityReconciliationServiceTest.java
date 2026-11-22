package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.application.service.CapacityReconciliationService.RepairResult;
import com.iitsm.domain.model.CapacityDiscrepancy;
import com.iitsm.domain.model.CapacityDiscrepancy.Kind;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.policy.CapacityAudit;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class CapacityReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private TechnicianRepository technicianRepository;
    private IncidentRepository incidentRepository;
    private CapacityReconciliationService service;

    @BeforeEach
    void setUp() {
        technicianRepository = mock(TechnicianRepository.class);
        incidentRepository = mock(IncidentRepository.class);
        when(incidentRepository.findActiveIncidents()).thenReturn(List.of());
        when(incidentRepository.findById(any())).thenReturn(Optional.empty());
        service = new CapacityReconciliationService(technicianRepository, incidentRepository);
    }

    private static Technician technician(String id, int maxCapacity) {
        return new Technician(id, "Tech " + id, new Email(id.toLowerCase() + "@test.com"), "hash",
                Set.of(new Skill("BGP", IncidentCategory.NETWORK)), 5, maxCapacity, "TEAM-NET");
    }

    private static Incident assignedTo(String id, String technicianId) {
        Incident incident = new Incident(id, "VPN keeps dropping", "The VPN client drops every hour for all of us.", "EMP-1", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null);
        incident.classify(IncidentCategory.NETWORK, "SVC-VPN", decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        incident.assign(technicianId, NOW);
        return incident;
    }

    @Test
    @DisplayName("The audit also looks up tickets a slot refers to that are not among the open ones")
    void auditFindsTheTicketsBehindSlots() {
        Technician tech = technician("TECH-1", 3);
        tech.assignIncident("INC-CLOSED");
        tech.assignIncident("INC-GHOST");
        when(technicianRepository.findAll()).thenReturn(List.of(tech));
        Incident closedElsewhere = assignedTo("INC-CLOSED", "TECH-1");
        when(incidentRepository.findById("INC-CLOSED")).thenReturn(Optional.of(closedElsewhere));

        CapacityAudit audit = service.audit();

        verify(incidentRepository).findById("INC-CLOSED");
        verify(incidentRepository).findById("INC-GHOST");
        assertThat(audit.discrepancies()).extracting(CapacityDiscrepancy::incidentId).containsExactly("INC-GHOST");
    }

    @Test
    @DisplayName("Repair releases a phantom slot and saves the technician once")
    void repairReleasesPhantomSlot() {
        Technician tech = technician("TECH-1", 3);
        tech.assignIncident("INC-GHOST");
        tech.assignIncident("INC-GHOST-2");
        when(technicianRepository.findAll()).thenReturn(List.of(tech));

        RepairResult result = service.repair();

        assertThat(result.repaired()).hasSize(2);
        assertThat(result.notRepaired()).isEmpty();
        assertThat(tech.getActiveIncidentIds()).isEmpty();
        verify(technicianRepository, times(1)).save(tech);
    }

    @Test
    @DisplayName("Repair takes a missing slot when the technician has room and says why when it has not")
    void repairMissingSlot() {
        Technician roomy = technician("TECH-1", 3);
        Technician full = technician("TECH-2", 1);
        full.assignIncident("INC-OTHER");
        when(technicianRepository.findAll()).thenReturn(List.of(roomy, full));
        Incident other = assignedTo("INC-OTHER", "TECH-2");
        when(incidentRepository.findActiveIncidents()).thenReturn(List.of(
                assignedTo("INC-A", "TECH-1"), assignedTo("INC-B", "TECH-2"), other));

        RepairResult result = service.repair();

        assertThat(result.repaired()).extracting(CapacityDiscrepancy::incidentId).containsExactly("INC-A");
        assertThat(roomy.getActiveIncidentIds()).containsExactly("INC-A");
        assertThat(result.notRepaired()).singleElement().satisfies(d -> {
            assertThat(d.incidentId()).isEqualTo("INC-B");
            assertThat(d.detail()).contains("not repaired").contains("no free capacity");
        });
        verify(technicianRepository).save(roomy);
        verify(technicianRepository, never()).save(full);
    }

    @Test
    @DisplayName("An orphaned duplicate is reported with its reason and never saved")
    void orphanedDuplicateIsOnlyReported() {
        Technician tech = technician("TECH-1", 3);
        when(technicianRepository.findAll()).thenReturn(List.of(tech));
        Incident child = assignedTo("INC-CHILD", "TECH-1");
        child.linkIncident("INC-PARENT");
        when(incidentRepository.findActiveIncidents()).thenReturn(List.of(child));

        RepairResult result = service.repair();

        assertThat(result.repaired()).isEmpty();
        assertThat(result.notRepaired()).singleElement().satisfies(d -> {
            assertThat(d.kind()).isEqualTo(Kind.ORPHANED_DUPLICATE);
            assertThat(d.detail()).contains("by design");
        });
        verify(technicianRepository, never()).save(any());
    }

    @Test
    @DisplayName("Nothing to repair: nothing is saved")
    void consistentStateChangesNothing() {
        Technician tech = technician("TECH-1", 3);
        tech.assignIncident("INC-1");
        when(technicianRepository.findAll()).thenReturn(List.of(tech));
        when(incidentRepository.findActiveIncidents()).thenReturn(List.of(assignedTo("INC-1", "TECH-1")));

        RepairResult result = service.repair();

        assertThat(result.repaired()).isEmpty();
        assertThat(result.notRepaired()).isEmpty();
        verify(technicianRepository, never()).save(any());
    }

    @Test
    @DisplayName("Carried duplicates are counted per technician")
    void carriedDuplicates() {
        Technician tech = technician("TECH-1", 3);
        tech.assignIncident("INC-PARENT");
        Incident parent = assignedTo("INC-PARENT", "TECH-1");
        Incident child = assignedTo("INC-CHILD", "TECH-1");
        parent.linkIncident("INC-CHILD");
        child.linkIncident("INC-PARENT");
        when(technicianRepository.findAll()).thenReturn(List.of(tech));
        when(incidentRepository.findActiveIncidents()).thenReturn(List.of(parent, child));

        assertThat(service.carriedDuplicatesByTechnician()).containsEntry("TECH-1", 1).hasSize(1);
    }

    @Test
    @DisplayName("Technicians and tickets are read in one snapshot (REPEATABLE_READ); a read between two commits could invent a leak")
    void readsAConsistentSnapshot() throws NoSuchMethodException {
        for (String method : List.of("audit", "repair", "carriedDuplicatesByTechnician")) {
            Transactional transactional = CapacityReconciliationService.class.getMethod(method).getAnnotation(Transactional.class);
            assertThat(transactional).as(method).isNotNull();
            assertThat(transactional.isolation()).as(method).isEqualTo(Isolation.REPEATABLE_READ);
        }
        assertThat(CapacityReconciliationService.class.getMethod("audit").getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(CapacityReconciliationService.class.getMethod("repair").getAnnotation(Transactional.class).readOnly()).isFalse();
    }
}
