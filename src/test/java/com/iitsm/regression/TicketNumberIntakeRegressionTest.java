package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.TicketNumber;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Clock;
import java.time.Year;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end guard for ticket numbering: a ticket raised through the real intake pipeline gets a
 * {@code DEPT-YEAR-SEQUENCE} id built from the reporter's department, and the id survives a reload.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class TicketNumberIntakeRegressionTest {

    /** Seeded demo employee, department "Engineering". */
    private static final String ENGINEERING_EMPLOYEE = "employee@nimbusfinserve.com";
    /** Seeded CEO, department "Executive". */
    private static final String EXECUTIVE_EMPLOYEE = "ceo@nimbusfinserve.com";

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanySeeder companySeeder;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    @Test
    @DisplayName("a raised ticket gets the reporter's department code, the current year and a sequence")
    void ticketIdIsDepartmentYearSequence() {
        Incident created = incidentService.createIncident(
                ENGINEERING_EMPLOYEE, "VPN keeps dropping", "The VPN drops every few minutes for me today.");

        assertThat(TicketNumber.isValid(created.getId())).isTrue();
        TicketNumber number = TicketNumber.parse(created.getId());
        assertThat(number.departmentCode()).isEqualTo("ENG");
        assertThat(number.year()).isEqualTo(Year.now(clock).getValue());
        assertThat(number.sequence()).isPositive();
        assertThat(incidentRepository.findById(created.getId())).isPresent();
    }

    @Test
    @DisplayName("consecutive tickets of one department get consecutive numbers; another department counts separately")
    void sequenceContinuesPerDepartment() {
        Incident first = incidentService.createIncident(
                ENGINEERING_EMPLOYEE, "Printer offline", "The office printer is offline since this morning.");
        Incident second = incidentService.createIncident(
                ENGINEERING_EMPLOYEE, "Laptop will not boot", "My laptop shows a black screen at startup.");
        Incident other = incidentService.createIncident(
                EXECUTIVE_EMPLOYEE, "Email sync broken", "My mailbox has not synced since yesterday evening.");

        TicketNumber a = TicketNumber.parse(first.getId());
        TicketNumber b = TicketNumber.parse(second.getId());
        TicketNumber c = TicketNumber.parse(other.getId());
        assertThat(b.sequence()).isEqualTo(a.sequence() + 1);
        assertThat(c.departmentCode()).isEqualTo("EXE");
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }
}
