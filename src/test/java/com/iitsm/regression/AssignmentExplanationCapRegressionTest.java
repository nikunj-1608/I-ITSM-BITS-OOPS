package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.TicketDispatchService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.repository.AssignmentDecisionRepository;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.assignment.AssignmentDecision;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-044: an assignment decision stores at most {@code iitsm.assignment.explanation-max-candidates} ranked candidates
 * (set to 2 here), always including the chosen technician, and remembers how many were ranked.
 */
@SpringBootTest(properties = "iitsm.assignment.explanation-max-candidates=2")
@ActiveProfiles("test")
@Tag("regression")
class AssignmentExplanationCapRegressionTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    @Autowired private TicketDispatchService dispatchService;
    @Autowired private AssignmentDecisionRepository decisions;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String ticketId;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("UPDATE incidents SET status = 'CLOSED' WHERE id = ?", ticketId);
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    @Test
    @DisplayName("the stored ranking is cut to the configured two candidates; the count says how many were ranked")
    void rankingIsCutToTheConfiguredSize() {
        Incident incident = new Incident("TST-CAP-" + System.nanoTime(), "Explanation cap",
                "A network ticket planted to test the stored ranking size.", "EMP-DEMO-01", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null);
        incident.classify(IncidentCategory.NETWORK, "SRV-GEN-001", decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        ticketId = incidentRepository.save(incident).getId();
        int networkTeam = technicianRepository.findBySupportTeamId("TEAM-NET").size();

        dispatchService.dispatchOne(ticketId, "SYSTEM");

        AssignmentDecision recorded = decisions.findNewestByIncidentId(ticketId, 5).get(0);
        assertThat(networkTeam).isGreaterThan(2);
        assertThat(recorded.candidates()).hasSize(2);
        assertThat(recorded.candidateCount()).isEqualTo(networkTeam);
        assertThat(recorded.candidates().get(0).technicianId()).isEqualTo(recorded.chosenTechnicianId());
    }
}
