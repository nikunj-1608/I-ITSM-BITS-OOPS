package com.iitsm.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.FactorContribution;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression: the explainable priority breakdown used to be computed at intake and then silently dropped,
 * because the factor_contributions table had no JPA mapping. Not @Transactional on purpose: every save and
 * load below is its own real database transaction, as in production.
 */
@SpringBootTest
@ActiveProfiles("test")
class FactorContributionPersistenceTest {

    private static final String INCIDENT_ID = "INC-FACTOR-01";
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanySeeder companySeeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedReferenceData() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM factor_contributions WHERE incident_id = ?", INCIDENT_ID);
        jdbcTemplate.update("DELETE FROM sla_trackers WHERE incident_id = ?", INCIDENT_ID);
        jdbcTemplate.update("DELETE FROM incidents WHERE id = ?", INCIDENT_ID);
    }

    private static List<FactorContribution> factors() {
        return List.of(
                new FactorContribution("ServiceCriticality", 1.0, 0.30, 0.30, "Mission critical service"),
                new FactorContribution("Impact", 0.5, 0.25, 0.125, "Moderate blast radius"),
                new FactorContribution("Urgency", 0.75, 0.20, 0.15, "Customer facing"),
                new FactorContribution("Aging", 0.0, 0.10, 0.0, "Brand new ticket"));
    }

    private Incident saveClassifiedIncident() {
        Incident incident = new Incident(
                INCIDENT_ID, "Payment gateway timeouts", "Card payments are timing out for all customers.", "EMP-DEMO-01", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P2, 0.575, factors(), List.of("RULE_X"), NOW, null, null);
        incident.classify(IncidentCategory.APPLICATION, "SVC-PAYMENT", decision, SlaPolicy.defaultFor(Priority.P2), NOW);
        return incidentRepository.save(incident);
    }

    private int storedFactorRows() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM factor_contributions WHERE incident_id = ?", Integer.class, INCIDENT_ID);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("Factor breakdown survives a save and reload, with values and calculator order intact")
    void factorBreakdownRoundTrips() {
        saveClassifiedIncident();

        Incident reloaded = incidentRepository.findById(INCIDENT_ID).orElseThrow();

        assertThat(reloaded.getPriorityDecision().factorContributions()).containsExactlyElementsOf(factors());
    }

    @Test
    @DisplayName("Re-saving the incident does not duplicate factor rows")
    void resavingDoesNotDuplicateRows() {
        Incident saved = saveClassifiedIncident();
        saved.assign("TECH-NET-1", NOW.plusSeconds(60));
        incidentRepository.save(saved);
        Incident again = incidentRepository.findById(INCIDENT_ID).orElseThrow();
        again.acknowledge("TECH-NET-1", NOW.plusSeconds(120));
        incidentRepository.save(again);

        assertThat(storedFactorRows()).isEqualTo(factors().size());
        assertThat(incidentRepository.findById(INCIDENT_ID).orElseThrow()
                .getPriorityDecision().factorContributions()).hasSize(factors().size());
    }

    @Test
    @DisplayName("A manual priority override keeps the original factor breakdown")
    void priorityOverrideKeepsBreakdown() {
        Incident saved = saveClassifiedIncident();
        saved.overridePriority(Priority.P1, "ADMIN-SYS-01", "CEO escalation", NOW.plusSeconds(30));
        incidentRepository.save(saved);

        Incident reloaded = incidentRepository.findById(INCIDENT_ID).orElseThrow();

        assertThat(reloaded.getPriorityDecision().priority()).isEqualTo(Priority.P1);
        assertThat(reloaded.getPriorityDecision().overriddenBy()).isEqualTo("ADMIN-SYS-01");
        assertThat(reloaded.getPriorityDecision().factorContributions()).containsExactlyElementsOf(factors());
    }
}
