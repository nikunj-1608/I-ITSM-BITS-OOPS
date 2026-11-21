package com.iitsm.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.repository.AssignmentDecisionRepository;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.assignment.AssignmentDecision;
import com.iitsm.domain.strategy.assignment.AssignmentRecommendation;
import com.iitsm.domain.strategy.assignment.PoolKind;
import com.iitsm.domain.strategy.assignment.WeightedCompositeStrategy;
import com.iitsm.infrastructure.persistence.CompanySeeder;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The assignment decision adapter against the real schema (ADR-044): a decision reads back unchanged, the newest come
 * first and the limit holds, and deleting the ticket removes its decisions.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssignmentDecisionRepositoryAdapterTest {

    private static final Instant T0 = Instant.parse("2026-11-08T08:00:00Z");

    @Autowired private AssignmentDecisionRepository repository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private String ticketId;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        ticketId = "TST-ADEC-" + System.nanoTime();
        incidentRepository.save(new Incident(ticketId, "Adapter test ticket",
                "A ticket that only carries assignment decisions for this test.", "EMP-DEMO-01", T0));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM incidents WHERE id = ?", ticketId);
    }

    private AssignmentRecommendation recommendation() {
        return new WeightedCompositeStrategy().recommend(
                com.iitsm.domain.strategy.assignment.AssignmentGridAccess.ticket(),
                com.iitsm.domain.strategy.assignment.AssignmentGridAccess.allTechnicians());
    }

    private AssignmentDecision save(AssignmentDecision decision) {
        return transactionTemplate.execute(status -> repository.save(decision));
    }

    @Test
    @DisplayName("a strategy decision with its ranking reads back exactly")
    void roundTrip() {
        AssignmentDecision decision = AssignmentDecision.byStrategy("ASG-T-" + System.nanoTime(), ticketId,
                PoolKind.SKILLED_OVERFLOW, recommendation(), "DISPATCHER", T0, 4);
        save(decision);

        assertThat(repository.findNewestByIncidentId(ticketId, 5)).containsExactly(decision);
    }

    @Test
    @DisplayName("the newest decisions come first and the limit holds")
    void newestFirstWithLimit() {
        for (int i = 0; i < 4; i++) {
            save(AssignmentDecision.manual("ASG-M-" + i + "-" + System.nanoTime(), ticketId, "TECH-NET-1",
                    "ADMIN-SYS-01", T0.plusSeconds(60L * i)));
        }
        List<AssignmentDecision> newest = repository.findNewestByIncidentId(ticketId, 3);

        assertThat(newest).extracting(AssignmentDecision::decidedAt)
                .containsExactly(T0.plusSeconds(180), T0.plusSeconds(120), T0.plusSeconds(60));
        assertThat(repository.findNewestByIncidentId(ticketId, 0)).isEmpty();
        assertThat(repository.findNewestByIncidentId(null, 3)).isEmpty();
    }

    @Test
    @DisplayName("deleting the ticket deletes its decisions (cascade)")
    void cascadeOnDelete() {
        save(AssignmentDecision.requeuedToSameTechnician("ASG-R-" + System.nanoTime(), ticketId, "TECH-NET-1",
                "ADMIN-SYS-01", T0));
        jdbcTemplate.update("DELETE FROM incidents WHERE id = ?", ticketId);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM assignment_decisions WHERE incident_id = ?",
                Integer.class, ticketId)).isZero();
    }
}
