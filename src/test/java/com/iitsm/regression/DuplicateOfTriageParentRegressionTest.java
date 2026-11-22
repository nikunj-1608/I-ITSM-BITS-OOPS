package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.UnassignedIncidentDispatcher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.util.List;
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
 * Remaining-defects plan, Phase I (ADR-038, test T1; CLAUDE.md section 7 item 20): a confident report that duplicates a
 * ticket sitting in triage. The parent has no technician, so the duplicate has nobody to ride (F4) and is dealt with
 * on its own merits: it is linked to the parent, is not in triage and gets a technician of its own, while the parent
 * stays with the humans in the triage queue and the dispatcher leaves it alone. This pins today's behaviour.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class DuplicateOfTriageParentRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String TITLE = "VPN gateway timeouts for the whole office";
    private static final String TEXT = "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.";

    @Autowired private IncidentService incidentService;
    @Autowired private UnassignedIncidentDispatcher dispatcher;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String parentId;
    private String childId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        // no other open ticket may become the parent of this report
        jdbcTemplate.update("UPDATE incidents SET status = 'CLOSED' WHERE status <> 'CLOSED'");
    }

    @AfterEach
    void tearDown() {
        List.of(parentId, childId).stream().filter(java.util.Objects::nonNull)
                .forEach(id -> jdbcTemplate.update("UPDATE incidents SET status = 'CLOSED' WHERE id = ?", id));
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    @Test
    @DisplayName("T1: at intake a confident duplicate of a parent in triage is linked and not in triage, but waits unassigned; the parent stays in triage")
    void confidentDuplicateOfATriageParentWaitsForTheDispatcher() {
        Incident parent = incidentService.createIncident(EMPLOYEE, TITLE, TEXT);
        parentId = parent.getId();
        jdbcTemplate.update("UPDATE incidents SET assignee_id = NULL, status = 'CLASSIFIED', needs_triage = TRUE WHERE id = ?", parentId);
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");

        Incident child = incidentService.createIncident(EMPLOYEE, TITLE, TEXT);
        childId = child.getId();

        Incident reloadedChild = incidentRepository.findById(childId).orElseThrow();
        Incident reloadedParent = incidentRepository.findById(parentId).orElseThrow();
        assertThat(reloadedChild.getLinkedIncidentIds()).as("the report is linked to the parent").contains(parentId);
        assertThat(reloadedChild.isNeedsTriage()).as("the report itself is confident").isFalse();
        assertThat(reloadedChild.getStatus()).as("a duplicate is never assigned at intake except by riding its parent").isEqualTo(IncidentStatus.CLASSIFIED);
        assertThat(reloadedChild.getAssigneeId()).as("the parent has nobody to ride").isNull();
        assertThat(reloadedParent.isNeedsTriage()).isTrue();
        assertThat(reloadedParent.getAssigneeId()).isNull();
        assertThat(reloadedParent.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
    }

    @Test
    @DisplayName("T1: the dispatcher then gives the duplicate a technician of its own and never touches the parent in triage")
    void dispatcherAssignsTheDuplicateAndLeavesTheTriageParentAlone() {
        Incident parent = incidentService.createIncident(EMPLOYEE, TITLE, TEXT);
        parentId = parent.getId();
        jdbcTemplate.update("UPDATE incidents SET assignee_id = NULL, status = 'CLASSIFIED', needs_triage = TRUE WHERE id = ?", parentId);
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        childId = incidentService.createIncident(EMPLOYEE, TITLE, TEXT).getId();

        dispatcher.dispatchWaitingIncidents();

        Incident reloadedChild = incidentRepository.findById(childId).orElseThrow();
        Incident reloadedParent = incidentRepository.findById(parentId).orElseThrow();
        assertThat(reloadedChild.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(reloadedChild.getAssigneeId()).isNotNull();
        assertThat(reloadedParent.isNeedsTriage()).isTrue();
        assertThat(reloadedParent.getAssigneeId()).isNull();
        assertThat(reloadedParent.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
    }
}
