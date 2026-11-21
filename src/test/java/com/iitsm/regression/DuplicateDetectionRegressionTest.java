package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end guard for ADR-016 through the real intake pipeline: tickets that only share template keywords or
 * filler are not clustered, and a resolved ticket is not a duplicate candidate.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class DuplicateDetectionRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";

    @Autowired private IncidentService incidentService;
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

    private static String word(Random random) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("Tickets sharing only three keywords and differing in all details are not clustered")
    void templateTicketsAreNotClustered() {
        Random random = new Random(2026);
        List<Incident> created = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String title = "Laptop keyboard " + word(random) + " " + word(random);
            StringBuilder description = new StringBuilder("The laptop and monitor");
            for (int w = 0; w < 18; w++) {
                description.append(' ').append(word(random));
            }
            created.add(incidentService.createIncident(EMPLOYEE, title, description.append(" keyboard.").toString()));
        }

        assertThat(created).allSatisfy(incident -> assertThat(incident.getLinkedIncidentIds()).isEmpty());
    }

    @Test
    @DisplayName("Two near-empty tickets with no technical word are never linked, even when identical")
    void fillerOnlyTicketsAreNotLinked() {
        String title = "Problem since this morning";
        String description = "Nothing obvious to add right now, sorry about that, please advise soon.";

        incidentService.createIncident(EMPLOYEE, title, description);
        Incident second = incidentService.createIncident(EMPLOYEE, title, description);

        assertThat(second.getLinkedIncidentIds()).isEmpty();
    }

    @Test
    @DisplayName("A resolved ticket is not a duplicate candidate: a new identical report becomes its own ticket")
    void resolvedTicketIsNotACandidate() {
        String title = "Qxzvbn gateway VPN tunnel flapping at the Nagpur branch";
        String description = "AnyConnect VPN tunnel to the Qxzvbn gateway flaps every few minutes, packet loss and latency.";
        Incident parent = incidentService.createIncident(EMPLOYEE, title, description);
        String technician = parent.getAssigneeId();
        assertThat(technician).isNotNull();
        incidentService.acknowledge(parent.getId(), technician);
        incidentService.startInvestigation(parent.getId(), technician);
        incidentService.resolve(parent.getId(), technician, new Resolution(
                "Stale tunnel", "Restarted the gateway", "Monitoring", "NETWORK", technician, Instant.now()));

        Incident again = incidentService.createIncident(EMPLOYEE, title, description);

        assertThat(again.getLinkedIncidentIds()).doesNotContain(parent.getId());
    }
}
