package com.iitsm.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.CapacityDiscrepancy;
import com.iitsm.domain.model.FactorContribution;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Proves the capacity check reports what it is meant to find; every change is rolled back. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CapacityInvariantTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final String REPORTER = "EMP-DEMO-01";
    private static final String SERVICE = "SVC-PAYMENT";

    @Autowired
    private TechnicianRepository technicianRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanySeeder companySeeder;

    @BeforeEach
    void seedReferenceData() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    @Test
    @DisplayName("A slot for a ticket that does not exist is reported as a phantom slot")
    void reportsPhantomSlot() {
        Technician technician = technicianWithFreeCapacity();
        technician.assignIncident("INC-CAPCHECK-GHOST");
        technicianRepository.save(technician);

        assertThat(violationsFor(technician))
                .anyMatch(v -> v.kind() == CapacityDiscrepancy.Kind.PHANTOM_SLOT
                        && v.incidentId().equals("INC-CAPCHECK-GHOST"));
    }

    @Test
    @DisplayName("An open, unlinked ticket naming a technician without a slot is reported as a missing slot")
    void reportsMissingSlot() {
        Technician technician = technicianWithFreeCapacity();
        incidentRepository.save(assignedTicket("INC-CAPCHECK-NOSLOT", technician.getId()));

        assertThat(violationsFor(technician))
                .anyMatch(v -> v.kind() == CapacityDiscrepancy.Kind.MISSING_SLOT
                        && v.incidentId().equals("INC-CAPCHECK-NOSLOT"));
    }

    @Test
    @DisplayName("A ticket that holds its slot produces no violation")
    void consistentAssignmentIsClean() {
        Technician technician = technicianWithFreeCapacity();
        incidentRepository.save(assignedTicket("INC-CAPCHECK-OK", technician.getId()));
        technician.assignIncident("INC-CAPCHECK-OK");
        technicianRepository.save(technician);

        assertThat(violationsFor(technician)).noneMatch(v -> v.incidentId().equals("INC-CAPCHECK-OK"));
    }

    private List<CapacityDiscrepancy> violationsFor(Technician technician) {
        return CapacityInvariant.check(technicianRepository, incidentRepository).stream()
                .filter(v -> v.technicianId().equals(technician.getId()))
                .toList();
    }

    private Technician technicianWithFreeCapacity() {
        return technicianRepository.findAll().stream()
                .filter(Technician::hasCapacity)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("seed has no technician with free capacity"));
    }

    private static Incident assignedTicket(String id, String technicianId) {
        Incident incident = new Incident(id, "Capacity check ticket", "Ticket used by the capacity check test.", REPORTER, NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.40,
                List.of(new FactorContribution("Crit", 0.4, 1.0, 0.4, "test")), List.of(), NOW, null, null);
        incident.classify(IncidentCategory.NETWORK, SERVICE, decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        incident.assign(technicianId, NOW);
        return incident;
    }
}
