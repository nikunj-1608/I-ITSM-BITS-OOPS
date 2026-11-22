package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.UnassignedIncidentDispatcher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end guard for automatic ticket assignment: realistic ticket text goes through the real classifier,
 * intake pipeline, technician pool and dispatcher against the seeded company.
 *
 * <p>Regression for: tickets silently staying unassigned after the human-triage gate was introduced.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class AutoAssignmentRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("no-reply@nimbusfinserve.com", "password"))
            .withPerMethodLifecycle(false);

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private UnassignedIncidentDispatcher dispatcher;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private TechnicianRepository technicianRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanySeeder companySeeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    @DisplayName("A ticket with a clear keyword in its title is auto-assigned at intake")
    void clearTicketIsAssignedAtIntake() {
        Incident created = incidentService.createIncident(
                EMPLOYEE,
                "VPN keeps dropping again",
                "It keeps dropping again today and I really cannot work like this, please help me soon.");

        assertThat(created.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(created.getAssigneeId()).isNotNull();
        assertThat(created.isNeedsTriage()).isFalse();
    }

    @Test
    @DisplayName("A vague ticket goes to the human triage queue and is not touched by the dispatcher")
    void vagueTicketStaysInTriageAndDispatcherSkipsIt() {
        Incident created = incidentService.createIncident(
                EMPLOYEE,
                "Something is not working properly",
                "Things seem slow and strange since yesterday afternoon, please have a look when you get some time.");

        dispatcher.dispatchWaitingIncidents();

        Incident reloaded = incidentRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.isNeedsTriage()).isTrue();
        assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        assertThat(reloaded.getAssigneeId()).isNull();
    }

    @Test
    @DisplayName("A confident ticket that found no free technician is assigned by the dispatcher once capacity frees")
    void waitingTicketIsAssignedByDispatcherWhenCapacityFrees() {
        fillAllTechnicians();
        Incident waiting = incidentService.createIncident(
                EMPLOYEE,
                "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway.");
        assertThat(waiting.getAssigneeId()).as("no capacity anywhere: must wait").isNull();
        assertThat(waiting.isNeedsTriage()).isFalse();

        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        dispatcher.dispatchWaitingIncidents();

        Incident reloaded = incidentRepository.findById(waiting.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(reloaded.getAssigneeId()).isNotNull();
    }

    private void fillAllTechnicians() {
        List<Technician> technicians = technicianRepository.findAll();
        for (Technician technician : technicians) {
            new HashSet<>(technician.getActiveIncidentIds()).forEach(technician::releaseIncident);
            for (int slot = 0; slot < technician.getMaxCapacity(); slot++) {
                technician.assignIncident("DUMMY-SLOT-" + slot);
            }
            technicianRepository.save(technician);
        }
    }
}
