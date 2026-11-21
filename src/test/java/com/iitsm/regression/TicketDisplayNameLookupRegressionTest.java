package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.AdminIncidentDetailService;
import com.iitsm.application.service.AdminIncidentService;
import com.iitsm.application.service.IncidentLifecycleService;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.web.dto.AdminIncidentDetailView;
import com.iitsm.web.mapper.IncidentDtoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end guard for the staff display name: a ticket raised through the real pipeline can be
 * opened and acted on by its plain id or by its priority-first display name, and the display name
 * follows an admin priority override while the id stays the same.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class TicketDisplayNameLookupRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";

    @Autowired
    private IncidentService incidentService;
    @Autowired
    private IncidentLifecycleService lifecycleService;
    @Autowired
    private AdminIncidentService adminIncidentService;
    @Autowired
    private AdminIncidentDetailService detailService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanySeeder companySeeder;

    @BeforeEach
    void seed() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    private Incident raise() {
        return incidentService.createIncident(
                EMPLOYEE, "VPN keeps dropping", "The VPN drops every few minutes for me today and I cannot work.");
    }

    @Test
    @DisplayName("a ticket opens by its display name (any priority prefix) as well as by its plain id")
    void ticketOpensByDisplayNameAndPlainId() {
        Incident created = raise();

        Incident byId = lifecycleService.getIncident(ADMIN_EMAIL, created.getId());
        Incident byName = lifecycleService.getIncident(ADMIN_EMAIL, "P2-" + created.getId());

        assertThat(byId.getId()).isEqualTo(created.getId());
        assertThat(byName.getId()).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("an admin override addressed by display name works, and the display name then shows the new priority")
    void overrideByDisplayNameUpdatesTheDisplayName() {
        Incident created = raise();
        Priority current = created.getPriorityDecision().priority();
        Priority other = current == Priority.P1 ? Priority.P4 : Priority.P1;

        adminIncidentService.overridePriority("P3-" + created.getId(), ADMIN_ID, other, "Board escalation");

        AdminIncidentDetailView view = IncidentDtoMapper.toAdminDetailView(
                detailService.getDetail(ADMIN_EMAIL, "P4-" + created.getId()));
        assertThat(view.incident().id()).isEqualTo(created.getId());
        assertThat(view.incident().displayId()).isEqualTo(other.name() + "-" + created.getId());
        assertThat(view.incident().priorityDecision().priority()).isEqualTo(other);
    }
}
