package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.ReassignmentRequest;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ReassignmentRequestRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
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
 * Guards critical defects C1 and C2 found by the 2026-10-31 stress audit (see docs/DECISIONS.md ADR-015).
 *
 * <p>C1: text containing {@code & ' < >} was re-escaped on every save/load until the ticket became un-editable.
 * C2: admin assign / accept-suggestion on a wrong-domain ticket failed with a foreign-key violation (email stored
 * as reviewer id).
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class CriticalDefectsRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";

    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private ReassignmentRequestRepository reassignmentRequestRepository;
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

    @Test
    @DisplayName("C1: a title with & and ' keeps its exact text and length across repeated saves")
    void titleIsNotReEscapedOnSaveAndLoad() {
        String title = "R&D VPN can't connect from the trading floor";
        Incident created = incidentService.createIncident(EMPLOYEE, title,
                "The VPN connection keeps dropping on the office wifi network since this morning.");

        Incident reloaded = created;
        for (int cycle = 0; cycle < 3; cycle++) {
            reloaded = incidentRepository.save(incidentRepository.findById(created.getId()).orElseThrow());
        }

        assertThat(reloaded.getTitle()).isEqualTo(title);
        assertThat(incidentRepository.findById(created.getId()).orElseThrow().getTitle()).isEqualTo(title);
    }

    @Test
    @DisplayName("C1: a 120-character title made only of apostrophes is accepted and stored verbatim")
    void maxLengthTitleWithEscapableCharactersIsStorable() {
        String title = "'".repeat(120);
        Incident created = incidentService.createIncident(EMPLOYEE, title,
                "The VPN connection keeps dropping on the office wifi network since this morning.");

        assertThat(incidentRepository.findById(created.getId()).orElseThrow().getTitle()).isEqualTo(title);
    }

    @Test
    @DisplayName("C2: admin can assign a wrong-domain ticket (login email is not stored as reviewer id)")
    void adminCanAssignWrongDomainTicket() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        assertThat(created.getAssigneeId()).isNotNull();
        incidentService.flagWrongDomain(created.getId(), created.getAssigneeId(), "Not my area");

        String otherTech = technicianRepository.findAll().stream()
                .map(Technician::getId).filter(id -> !id.equals(created.getAssigneeId())).findFirst().orElseThrow();
        Incident assigned = incidentService.assign(created.getId(), ADMIN_EMAIL, otherTech);

        assertThat(assigned.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(assigned.getAssigneeId()).isEqualTo(otherTech);
        assertThat(reassignmentRequestRepository.findByIncidentId(created.getId()))
                .allSatisfy(req -> {
                    assertThat(req.getStatus()).isEqualTo(ReassignmentRequest.Status.APPROVED);
                    assertThat(req.getReviewedByAdminId()).isEqualTo("ADMIN-SYS-01");
                });
    }
}
