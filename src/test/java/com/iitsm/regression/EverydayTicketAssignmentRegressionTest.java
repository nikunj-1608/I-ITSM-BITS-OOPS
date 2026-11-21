package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-052: short, plain-language tickets that employees really write are assigned at intake, and vague or non-IT ones
 * still wait for a person. Whole application, real classifier, real routing, seeded company.
 *
 * <p>Regression for: clear everyday tickets (printer, internet, account locked, e-mail, a database error) silently
 * staying in the triage queue (found on 2026-11-21, 8 of 25 live tickets). A ticket that is a duplicate of one an earlier
 * test left open rides that ticket's technician, so the assertion is "not in triage and assigned".
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class EverydayTicketAssignmentRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";

    @Autowired
    private IncidentService incidentService;

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

    static Stream<Arguments> clearEverydayTickets() {
        return Stream.of(
                Arguments.of("Printer not working", "The office printer on floor 3 does not print my documents."),
                Arguments.of("Server unreachable", "The build server is unreachable from my machine."),
                Arguments.of("Need software installed", "Please install Python on my machine."),
                Arguments.of("Email not sending", "My emails are stuck in the outbox."),
                Arguments.of("Account locked", "My account got locked after a few attempts."),
                Arguments.of("Antivirus found a virus", "My antivirus says it found a virus on my laptop and quarantined a file."),
                Arguments.of("Azure VM not starting", "Our Azure virtual machine for testing does not start."),
                Arguments.of("Report fails with database error", "The monthly report fails with a database error when I run it."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clearEverydayTickets")
    @DisplayName("A clear everyday ticket is assigned at intake and is not in the triage queue")
    void clearEverydayTicketIsAssignedAtIntake(String title, String description) {
        Incident created = incidentService.createIncident(EMPLOYEE, title, description);

        assertThat(created.isNeedsTriage()).as("in triage: " + created.getCategory()).isFalse();
        assertThat(created.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(created.getAssigneeId()).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ticketsForAPerson")
    @DisplayName("A vague or non-IT ticket still waits in the triage queue")
    void vagueOrNonItTicketStaysInTriage(String title, String description) {
        Incident created = incidentService.createIncident(EMPLOYEE, title, description);

        assertThat(created.isNeedsTriage()).isTrue();
        assertThat(created.getAssigneeId()).isNull();
        assertThat(created.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
    }

    static Stream<Arguments> ticketsForAPerson() {
        return Stream.of(
                Arguments.of("Kitchen tap leaking", "The tap in the floor 2 kitchen is leaking water."),
                Arguments.of("Desk drawer locked, lost the pin", "The drawer of my office desk is locked and I lost the PIN for the key pad."),
                Arguments.of("It does not work", "It does not work since yesterday."));
    }

    @Test
    @DisplayName("A security term in a ticket another category wins is still left to a person")
    void securityIndicatorStaysWithAPerson() {
        Incident created = incidentService.createIncident(EMPLOYEE, "Phishing email opened Excel macro",
                "I opened an attachment from an unknown sender and enabled the Excel macro it asked for.");

        assertThat(created.getAssigneeId()).as("a security person decides").isNull();
    }
}
