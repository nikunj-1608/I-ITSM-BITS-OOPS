package com.iitsm.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.assignment.ServiceExperienceSource;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The service-experience adapter against the real schema (ADR-044, Phase L4): how many tickets of one IT service each
 * technician has resolved, counting only fixes that stand.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServiceExperienceAdapterTest {

    private static final String PREFIX = "SXP-";
    private static final String SERVICE = "SVC-SSO";
    private static final String OTHER_SERVICE = "SVC-CRM";
    private static final Instant BASE = Instant.parse("2099-01-01T00:00:00Z");

    @Autowired private ServiceExperienceSource source;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM resolutions WHERE incident_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM incidents WHERE id LIKE ?", PREFIX + "%");
    }

    private void ticket(String suffix, String service, String status, String resolvedBy) {
        String id = PREFIX + suffix;
        jdbc.update("INSERT INTO incidents (id, title, description, reporter_id, category, priority, status, service_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, "Title of " + suffix, "Description of the history ticket " + suffix, "EMP-DEMO-01", "NETWORK", "P3", status, service, Timestamp.from(BASE));
        if (resolvedBy != null) {
            jdbc.update("INSERT INTO resolutions (incident_id, root_cause, steps_taken, preventive_action, confirmed_category, "
                            + "resolved_by, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, "Root", "Steps", "Prevent", "NETWORK", resolvedBy, Timestamp.from(BASE));
        }
    }

    @Test
    @DisplayName("it counts the tickets of the service each technician resolved, and only for the technicians asked about")
    void countsPerTechnician() {
        ticket("A1", SERVICE, "CLOSED", "TECH-SXP-1");
        ticket("A2", SERVICE, "VERIFIED", "TECH-SXP-1");
        ticket("A3", SERVICE, "RESOLVED", "TECH-SXP-1");
        ticket("B1", SERVICE, "CLOSED", "TECH-SXP-2");
        ticket("C1", SERVICE, "CLOSED", "TECH-SXP-4");

        Map<String, Integer> counts = source.resolvedTicketCounts(SERVICE, List.of("TECH-SXP-1", "TECH-SXP-2", "TECH-SXP-3"));

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("TECH-SXP-1", 3, "TECH-SXP-2", 1));
    }

    @Test
    @DisplayName("another service does not count, a ticket sent back (in progress again) does not count, nor does one without a resolution")
    void onlyFixesThatStandForThisService() {
        ticket("S1", SERVICE, "CLOSED", "TECH-SXP-1");
        ticket("OTHER", OTHER_SERVICE, "CLOSED", "TECH-SXP-1");
        ticket("BACK", SERVICE, "IN_PROGRESS", "TECH-SXP-1");
        ticket("OPEN", SERVICE, "ACKNOWLEDGED", null);
        ticket("NEW", SERVICE, "CLASSIFIED", null);

        assertThat(source.resolvedTicketCounts(SERVICE, List.of("TECH-SXP-1"))).containsExactly(Map.entry("TECH-SXP-1", 1));
        assertThat(source.resolvedTicketCounts(OTHER_SERVICE, List.of("TECH-SXP-1"))).containsExactly(Map.entry("TECH-SXP-1", 1));
    }

    @Test
    @DisplayName("nothing to ask: no service, no technicians, an unknown service or an unknown technician give an empty answer")
    void emptyAnswers() {
        ticket("S1", SERVICE, "CLOSED", "TECH-SXP-1");

        assertThat(source.resolvedTicketCounts(null, List.of("TECH-SXP-1"))).isEmpty();
        assertThat(source.resolvedTicketCounts(" ", List.of("TECH-SXP-1"))).isEmpty();
        assertThat(source.resolvedTicketCounts(SERVICE, List.of())).isEmpty();
        assertThat(source.resolvedTicketCounts(SERVICE, null)).isEmpty();
        assertThat(source.resolvedTicketCounts("SVC-NOBODY-HAS-THIS", List.of("TECH-SXP-1"))).isEmpty();
        assertThat(source.resolvedTicketCounts(SERVICE, List.of("TECH-UNKNOWN"))).isEmpty();
    }

    @Test
    @DisplayName("the bean in the application is this adapter, answering from the real database")
    void isTheAdapter() {
        assertThat(source).isInstanceOf(ServiceExperienceAdapter.class);
    }
}
