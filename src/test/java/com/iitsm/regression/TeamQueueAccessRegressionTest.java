package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Remaining-defects plan, Phase E (ADR-032, stress-audit item M2, decision D10): the team queue loaded every ticket and
 * kept those whose service the actor's team owns, without asking the access policy per ticket, so a technician saw the
 * tickets (and the internal notes) that colleagues were working on. Now: a technician sees the tickets assigned to
 * them and the unassigned tickets of their team; a team lead the whole team; an administrator everything.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class TeamQueueAccessRegressionTest {

    private static final List<String> NETWORK_TEAM = List.of("tech.network@nimbusfinserve.com",
            "tech.net2@nimbusfinserve.com", "tech.net3@nimbusfinserve.com");
    private static final String NETWORK_LEAD = "lead.network@nimbusfinserve.com";
    private static final String OTHER_TEAM_TECHNICIAN = "tech.db1@nimbusfinserve.com";
    private static final String ADMIN = "admin@nimbusfinserve.com";
    private static final String REGULAR_TECHNICIAN = "TECH-NET-2";

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

    /**
     * A network ticket held by a regular technician of the team. The ticket goes through the real intake, then its
     * technician is set explicitly: the same text in other tests makes this one a duplicate that rides its parent's
     * technician (F4), who may be the team lead.
     */
    private Incident networkTicket() {
        Incident created = incidentService.createIncident("employee@nimbusfinserve.com",
                "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        jdbcTemplate.update("UPDATE incidents SET assignee_id = ?, status = 'ASSIGNED' WHERE id = ?", REGULAR_TECHNICIAN, created.getId());
        return incidentService.getIncident(ADMIN, created.getId());
    }

    private String emailOf(String userId) {
        return userRepository.findById(userId).orElseThrow().getEmail().value();
    }

    private List<String> queueOf(String email) {
        return incidentService.getTeamQueue(email).stream().map(Incident::getId).toList();
    }

    /** A colleague of the assignee in the same team, never the assignee. */
    private String colleagueOf(String assigneeEmail) {
        return NETWORK_TEAM.stream().filter(email -> !email.equals(assigneeEmail)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("M2: a technician's team queue shows their own ticket but not the ticket of a colleague")
    void technicianDoesNotSeeColleaguesTickets() {
        Incident ticket = networkTicket();
        String assignee = emailOf(ticket.getAssigneeId());
        assertThat(assignee).isEqualTo("tech.net2@nimbusfinserve.com");

        assertThat(queueOf(assignee)).contains(ticket.getId());
        assertThat(queueOf(colleagueOf(assignee))).doesNotContain(ticket.getId());
    }

    @Test
    @DisplayName("D10: a team lead sees the whole team's tickets, an administrator everything")
    void leadAndAdministratorSeeMore() {
        Incident ticket = networkTicket();

        assertThat(queueOf(NETWORK_LEAD)).contains(ticket.getId());
        assertThat(queueOf(ADMIN)).contains(ticket.getId());
    }

    @Test
    @DisplayName("D10: a technician of another team never sees it")
    void otherTeamDoesNotSeeIt() {
        Incident ticket = networkTicket();

        assertThat(queueOf(OTHER_TEAM_TECHNICIAN)).doesNotContain(ticket.getId());
        assertThat(queueOf("lead.db@nimbusfinserve.com")).doesNotContain(ticket.getId());
    }

    @Test
    @DisplayName("D10: an unassigned ticket of the team is in the queue of every technician of that team, and only theirs")
    void unassignedTeamTicketIsShared() {
        Incident ticket = networkTicket();
        jdbcTemplate.update("UPDATE incidents SET assignee_id = NULL, status = 'CLASSIFIED' WHERE id = ?", ticket.getId());
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");

        for (String member : NETWORK_TEAM) {
            assertThat(queueOf(member)).as(member).contains(ticket.getId());
        }
        assertThat(queueOf(NETWORK_LEAD)).contains(ticket.getId());
        assertThat(queueOf(OTHER_TEAM_TECHNICIAN)).doesNotContain(ticket.getId());
    }

    @Test
    @DisplayName("ADR-036: the team queue follows the ticket's category, not the owner of its service")
    void teamQueueFollowsTheCategory() {
        Incident ticket = networkTicket();
        // a network ticket on a service the Cloud team owns, still waiting for a technician
        jdbcTemplate.update("UPDATE incidents SET service_id = 'SVC-PAYMENT', assignee_id = NULL, status = 'CLASSIFIED' WHERE id = ?",
                ticket.getId());
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");

        assertThat(queueOf(NETWORK_TEAM.getFirst())).contains(ticket.getId());
        assertThat(queueOf(NETWORK_LEAD)).contains(ticket.getId());
        assertThat(queueOf("lead.cloud@nimbusfinserve.com")).doesNotContain(ticket.getId());
        assertThat(queueOf("tech.cloud1@nimbusfinserve.com")).doesNotContain(ticket.getId());
    }

    @Test
    @DisplayName("ADR-036: who may open an unassigned ticket follows its category too, not the owner of its service")
    void ticketAccessFollowsTheCategory() {
        Incident ticket = networkTicket();
        jdbcTemplate.update("UPDATE incidents SET service_id = 'SVC-PAYMENT', assignee_id = NULL, status = 'CLASSIFIED' WHERE id = ?",
                ticket.getId());
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");

        assertThat(incidentService.getIncident(NETWORK_TEAM.getFirst(), ticket.getId()).getId()).isEqualTo(ticket.getId());
        assertThat(incidentService.getIncident(NETWORK_LEAD, ticket.getId()).getId()).isEqualTo(ticket.getId());
        assertThatThrownBy(() -> incidentService.getIncident("tech.cloud1@nimbusfinserve.com", ticket.getId()))
                .isInstanceOf(UnauthorizedOperationException.class);
    }

    @Test
    @DisplayName("Employees still cannot read a team queue")
    void employeesAreRefused() {
        assertThatThrownBy(() -> incidentService.getTeamQueue("employee@nimbusfinserve.com"))
                .isInstanceOf(UnauthorizedOperationException.class);
    }

    @Test
    @DisplayName("The team queue of a user that does not exist is refused, not empty")
    void unknownUserIsRefused() {
        assertThat(userRepository.findByEmail(new Email("nobody@nimbusfinserve.com"))).isEmpty();
        assertThatThrownBy(() -> incidentService.getTeamQueue("nobody@nimbusfinserve.com"))
                .isInstanceOf(RuntimeException.class);
    }
}
