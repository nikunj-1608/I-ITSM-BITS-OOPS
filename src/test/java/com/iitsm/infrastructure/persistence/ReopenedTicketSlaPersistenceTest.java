package com.iitsm.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import java.time.Duration;
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
 * H5: the reopened SLA state must survive a save and a load. {@code resolved_at} goes from a value back to
 * NULL, which a mapper that only copies non-null fields would lose. Not @Transactional on purpose: every
 * save and load is its own real database transaction, as in production.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReopenedTicketSlaPersistenceTest {

    private static final String INCIDENT_ID = "INC-REOPEN-01";
    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");

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

    private Instant at(long minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }

    private Incident saveResolvedTicket() {
        Incident incident = new Incident(
                INCIDENT_ID, "Payment gateway timeouts", "Card payments are timing out for all customers.",
                "EMP-DEMO-01", T0);
        PriorityDecision decision = new PriorityDecision(Priority.P1, 0.9, List.of(), List.of(), T0, null, null);
        incident.classify(IncidentCategory.APPLICATION, "SVC-PAYMENT", decision, SlaPolicy.defaultFor(Priority.P1), T0);
        incident.assign("TECH-NET-1", T0);
        incident.acknowledge("TECH-NET-1", at(1));
        incident.startInvestigation("TECH-NET-1", at(2));
        incident.resolve(new Resolution("Root cause", "Steps", "Prevention", "SOFTWARE", "TECH-NET-1", at(10)), at(10));
        return incidentRepository.save(incident);
    }

    @Test
    @DisplayName("H5: a rejected fix is stored with resolved_at NULL and no MET status")
    void reopenedSlaStateIsPersisted() {
        saveResolvedTicket();
        Incident resolved = incidentRepository.findById(INCIDENT_ID).orElseThrow();
        assertThat(resolved.getSlaTracker().getStatus()).isEqualTo(SlaStatus.MET);
        assertThat(resolved.getSlaTracker().getResolvedAt()).isEqualTo(at(10));

        resolved.reject("EMP-DEMO-01", "Still failing", at(14));
        incidentRepository.save(resolved);

        Incident reloaded = incidentRepository.findById(INCIDENT_ID).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
        assertThat(reloaded.getSlaTracker().getResolvedAt()).isNull();
        assertThat(reloaded.getSlaTracker().getStatus()).isNotEqualTo(SlaStatus.MET);
        assertThat(reloaded.getSlaTracker().getResolutionDueAt()).isEqualTo(at(34));
        Object storedResolvedAt = jdbcTemplate.queryForObject(
                "SELECT resolved_at FROM sla_trackers WHERE incident_id = ?", Object.class, INCIDENT_ID);
        assertThat(storedResolvedAt).isNull();
    }

    @Test
    @DisplayName("H5: the reopened ticket is in the SLA scan set and its breach is persisted")
    void reopenedTicketIsScannedAndBreachIsStored() {
        saveResolvedTicket();
        Incident resolved = incidentRepository.findById(INCIDENT_ID).orElseThrow();
        resolved.reject("EMP-DEMO-01", "Still failing", at(14));
        incidentRepository.save(resolved);

        Incident scanned = incidentRepository.findActiveIncidents().stream()
                .filter(i -> i.getId().equals(INCIDENT_ID))
                .findFirst()
                .orElseThrow();
        scanned.updateSlaStatus(at(120));
        incidentRepository.save(scanned);

        Incident reloaded = incidentRepository.findById(INCIDENT_ID).orElseThrow();
        assertThat(reloaded.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);
        assertThat(reloaded.getSlaTracker().getResolvedAt()).isNull();
    }
}
