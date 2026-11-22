package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.assignment.AssignmentGridAccess;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.infrastructure.config.AdjustableClock;
import com.iitsm.infrastructure.observer.TransactionAwareDomainEventPublisher;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR-044, Phase L3: the offline comparison writes nothing and moves nothing. Over the real database, clock, event
 * publisher and strategy registry: the same row counts before and after, no simulated ticket anywhere, the clock where it
 * was, no event published, the active strategy and the live round-robin rotation unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class StrategyComparisonNoSideEffectsRegressionTest {

    private static final List<String> TABLES = List.of("incidents", "audit_records", "notifications", "assignment_decisions",
            "technician_active_incidents", "ticket_sequences", "reassignment_requests", "suggestion_votes",
            "knowledge_articles", "users", "technician_skills", "factor_contributions", "incident_linked_ids");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;
    @Autowired private TransactionAwareDomainEventPublisher eventPublisher;
    @Autowired private AssignmentStrategyRegistry registry;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    private Map<String, Long> snapshot() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        counts.put("users.version", jdbcTemplate.queryForObject("SELECT COALESCE(SUM(version), 0) FROM users", Long.class));
        counts.put("incidents.version", jdbcTemplate.queryForObject("SELECT COALESCE(SUM(version), 0) FROM incidents", Long.class));
        return counts;
    }

    private void compare(String body) throws Exception {
        User admin = userRepository.findById("ADMIN-SYS-01").orElseThrow();
        mockMvc.perform(post("/api/simulation/strategy-comparison")
                        .header("Authorization", "Bearer " + tokenProvider.generateToken(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private AssignmentStrategy liveRoundRobin() {
        return registry.getAllStrategies().stream().filter(s -> s.getStrategyName().equals("ROUND_ROBIN")).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("no row is added or changed, and no simulated ticket (SIM-...) reaches any table")
    void databaseIsUntouched() throws Exception {
        Map<String, Long> before = snapshot();

        compare("{\"ticketCount\": 300, \"replications\": 4}");

        assertThat(snapshot()).isEqualTo(before);
        for (String table : List.of("incidents", "audit_records", "assignment_decisions", "technician_active_incidents")) {
            String column = table.equals("incidents") ? "id" : (table.equals("technician_active_incidents") ? "incident_id" : "incident_id");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " LIKE 'SIM-%'", Long.class))
                    .as(table).isZero();
        }
    }

    @Test
    @DisplayName("the live clock is not read into the future and not moved: same speed, no jump")
    void liveClockIsNotMoved() throws Exception {
        AdjustableClock adjustable = (AdjustableClock) clock;
        long speed = adjustable.getSpeedMultiplier();
        Instant before = adjustable.instant();

        compare("{\"ticketCount\": 300, \"replications\": 4}");

        assertThat(adjustable.getSpeedMultiplier()).isEqualTo(speed);
        assertThat(java.time.Duration.between(before, adjustable.instant())).as("only real time passed")
                .isLessThan(java.time.Duration.ofSeconds(20)).isGreaterThanOrEqualTo(java.time.Duration.ZERO);
    }

    @Test
    @DisplayName("no domain event is published and none is queued for delivery")
    void noEvents() throws Exception {
        List<?> history = List.copyOf(eventPublisher.getPublishedHistory());

        compare("{\"ticketCount\": 200, \"replications\": 3}");

        assertThat(eventPublisher.getPublishedHistory()).hasSameSizeAs(history);
    }

    @Test
    @DisplayName("the active strategy and the live round-robin rotation are exactly as before; the live strategies are the same objects")
    void liveStrategiesAreUntouched() throws Exception {
        String active = registry.getActiveStrategyName();
        List<AssignmentStrategy> strategies = registry.getAllStrategies();
        AssignmentStrategy roundRobin = liveRoundRobin();
        Incident ticket = AssignmentGridAccess.ticket();
        List<Technician> candidates = AssignmentGridAccess.allTechnicians();
        roundRobin.recommend(ticket, candidates);
        roundRobin.recommend(ticket, candidates);
        roundRobin.recommend(ticket, candidates);
        String nextTurn = roundRobin.rank(ticket, candidates).get(0).technicianId();

        compare("{\"ticketCount\": 400, \"replications\": 5, \"strategies\": [\"ROUND_ROBIN\", \"LOAD_BASED\"]}");

        assertThat(registry.getActiveStrategyName()).isEqualTo(active);
        assertThat(registry.getAllStrategies()).containsExactlyInAnyOrderElementsOf(strategies);
        assertThat(liveRoundRobin()).isSameAs(roundRobin);
        assertThat(roundRobin.rank(ticket, candidates).get(0).technicianId()).as("the live rotation did not move").isEqualTo(nextTurn);
    }
}
