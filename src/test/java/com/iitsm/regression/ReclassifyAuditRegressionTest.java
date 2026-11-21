package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.Feedback;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.repository.AuditRepository;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H7 follow-up: the audit trail of a reclassify must say what happened. Before the fix the service always
 * published {@code PriorityCalculated} with the recomputed priority, even when the ticket kept its manual
 * override or its earned SLA verdict, so the trail named a priority the ticket did not have.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class ReclassifyAuditRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";

    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private ItServiceRepository itServiceRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    private Incident assignedTicket() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        return created;
    }

    private ItService serviceOutsideTeamOf(String technicianId) {
        String team = technicianRepository.findById(technicianId).orElseThrow().getSupportTeamId();
        return itServiceRepository.findAll().stream()
                .filter(s -> s.getOwningTeamId() != null && !s.getOwningTeamId().equals(team))
                .findFirst().orElseThrow();
    }

    private List<AuditRecord> records(String incidentId, String action) {
        return auditRepository.findByIncidentIdOrderByTimestampAsc(incidentId).stream()
                .filter(r -> action.equals(r.action())).toList();
    }

    private void reclassify(Incident ticket) {
        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY,
                serviceOutsideTeamOf(ticket.getAssigneeId()).getId(), "Belongs to another team");
    }

    @Test
    @DisplayName("H7 A1: a reclassify that keeps a manual override does not record a calculated priority")
    void overrideKeptMeansNoPriorityCalculatedEntry() {
        Incident ticket = assignedTicket();
        Assumptions.assumeTrue(ticket.getPriority() != Priority.P1);
        incidentService.overridePriority(ticket.getId(), ADMIN_EMAIL, Priority.P1, "Business impact is higher");
        int calculatedBefore = records(ticket.getId(), "PriorityCalculated").size();
        int classifiedBefore = records(ticket.getId(), "IncidentClassified").size();

        reclassify(ticket);

        assertThat(records(ticket.getId(), "PriorityCalculated")).hasSize(calculatedBefore);
        assertThat(records(ticket.getId(), "IncidentClassified")).hasSize(classifiedBefore + 1);
    }

    @Test
    @DisplayName("H7 A2: a reclassify of a closed ticket does not record a calculated priority")
    void closedTicketMeansNoPriorityCalculatedEntry() {
        Incident ticket = assignedTicket();
        String tech = ticket.getAssigneeId();
        incidentService.acknowledge(ticket.getId(), tech);
        incidentService.startInvestigation(ticket.getId(), tech);
        incidentService.resolve(ticket.getId(), tech,
                new Resolution("Root cause", "Steps", "Prevention", "SOFTWARE", tech, Instant.now()));
        incidentService.verify(ticket.getId(), EMPLOYEE);
        incidentService.close(ticket.getId(), EMPLOYEE, new Feedback(5, "Thanks", Instant.now()));
        int calculatedBefore = records(ticket.getId(), "PriorityCalculated").size();

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY,
                serviceOutsideTeamOf(tech).getId(), "Label correction");

        assertThat(records(ticket.getId(), "PriorityCalculated")).hasSize(calculatedBefore);
    }

    @Test
    @DisplayName("H7 A3: a reclassify that applies a new priority records exactly that priority")
    void appliedPriorityIsTheOneRecorded() {
        Incident ticket = assignedTicket();
        int calculatedBefore = records(ticket.getId(), "PriorityCalculated").size();

        reclassify(ticket);

        List<AuditRecord> calculated = records(ticket.getId(), "PriorityCalculated");
        assertThat(calculated).hasSize(calculatedBefore + 1);
        Priority actual = incidentRepository.findById(ticket.getId()).orElseThrow().getPriority();
        assertThat(calculated.get(calculated.size() - 1).description()).contains("priority " + actual + " ");
    }
}
