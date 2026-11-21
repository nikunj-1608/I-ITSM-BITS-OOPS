package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.SlaService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.config.AdjustableClock;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ADR-049: every admin incident filter works over HTTP, alone and combined (AND between filters, OR inside one).
 *
 * <p>Two tickets are raised through the real intake, both carrying a run-unique word so the text filter limits every
 * answer to them. Ticket A is made P1, the simulation clock is moved past its response target and the SLA evaluation
 * marks it BREACHED; it is then acknowledged (a late response keeps the breach, ADR-030). Ticket B stays as the
 * intake left it. The tickets are closed afterwards, because open ones would become duplicate parents for other
 * classes (ADR-037 note); the class seeds its own data and passes alone (ADR-025).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class AdminSearchFiltersRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";
    private static final String URL = "/api/admin/incidents";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private SlaService slaService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    private final List<String> createdTickets = new ArrayList<>();
    private AdjustableClock adjustable;
    private String token;
    private String marker;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        Assumptions.assumeTrue(clock instanceof AdjustableClock);
        adjustable = (AdjustableClock) clock;
        User admin = userRepository.findById("ADMIN-SYS-01").orElseThrow();
        token = "Bearer " + tokenProvider.generateToken(admin);
        marker = "zfq" + Long.toString(System.nanoTime(), 36);
    }

    @AfterEach
    void tearDown() {
        if (adjustable != null) {
            adjustable.resetToRealTime();
        }
        createdTickets.forEach(id -> jdbcTemplate.update("UPDATE incidents SET status = 'CLOSED' WHERE id = ?", id));
        createdTickets.clear();
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    private Incident raise(String title, String description) {
        Incident created = incidentService.createIncident(EMPLOYEE, title + " " + marker, description);
        createdTickets.add(created.getId());
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        return created;
    }

    /** Ticket A: P1, BREACHED for a late response, then acknowledged. Ticket B: as the intake left it. */
    private Incident[] twoTickets() {
        Incident a = raise("VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        Incident b = raise("Database replication lag on the reporting cluster",
                "SQL queries on the database server time out because replication lag keeps growing.");
        incidentService.overridePriority(a.getId(), ADMIN_EMAIL, Priority.P1, "Whole office is offline");
        adjustable.fastForward(Duration.ofMinutes(10));
        slaService.evaluateIncident(a.getId());
        slaService.evaluateIncident(b.getId());
        incidentService.acknowledge(a.getId(), a.getAssigneeId());
        a = incidentRepository.findById(a.getId()).orElseThrow();
        b = incidentRepository.findById(b.getId()).orElseThrow();
        Assumptions.assumeTrue(a.getSlaTracker().getStatus() == SlaStatus.BREACHED, "A must be breached");
        Assumptions.assumeTrue(b.getSlaTracker().getStatus() != SlaStatus.BREACHED, "B must not be breached");
        Assumptions.assumeTrue(a.getStatus() != b.getStatus(), "the two tickets must differ in status");
        return new Incident[] {a, b};
    }

    private Set<String> ids(String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(URL).header("Authorization", token).param("query", marker);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        String body = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> found = JsonPath.read(body, "$[*].id");
        return new HashSet<>(found);
    }

    @Test
    @DisplayName("status: one value, several values (OR) and a value no ticket has")
    void statusFilter() throws Exception {
        Incident[] t = twoTickets();
        assertThat(ids("status", t[0].getStatus().name())).containsExactly(t[0].getId());
        assertThat(ids("status", t[0].getStatus().name(), "status", t[1].getStatus().name()))
                .containsExactlyInAnyOrder(t[0].getId(), t[1].getId());
        assertThat(ids("status", "CLOSED")).isEmpty();
    }

    @Test
    @DisplayName("slaStatus: the stored SLA status after the evaluation, one and several values")
    void slaStatusFilter() throws Exception {
        Incident[] t = twoTickets();
        assertThat(ids("slaStatus", "BREACHED")).containsExactly(t[0].getId());
        assertThat(ids("slaStatus", "BREACHED", "slaStatus", t[1].getSlaTracker().getStatus().name()))
                .containsExactlyInAnyOrder(t[0].getId(), t[1].getId());
        assertThat(ids("slaStatus", "MET")).isEmpty();
    }

    @Test
    @DisplayName("serviceId and assigneeId select the ticket that carries them")
    void serviceAndTechnicianFilters() throws Exception {
        Incident[] t = twoTickets();
        Assumptions.assumeTrue(t[0].getServiceId() != null && !t[0].getServiceId().equals(t[1].getServiceId()));
        Assumptions.assumeTrue(!t[0].getAssigneeId().equals(t[1].getAssigneeId()));

        assertThat(ids("serviceId", t[0].getServiceId())).containsExactly(t[0].getId());
        assertThat(ids("assigneeId", t[0].getAssigneeId())).containsExactly(t[0].getId());
        assertThat(ids("assigneeId", t[1].getAssigneeId())).containsExactly(t[1].getId());
    }

    @Test
    @DisplayName("fromDate/toDate: a window around the creation time includes, an earlier window excludes")
    void dateWindow() throws Exception {
        Incident[] t = twoTickets();
        Instant created = t[0].getCreatedAt();
        assertThat(ids("fromDate", created.minusSeconds(60).toString(), "toDate", created.plusSeconds(3600).toString()))
                .contains(t[0].getId(), t[1].getId());
        assertThat(ids("fromDate", created.minusSeconds(7200).toString(), "toDate", created.minusSeconds(3600).toString()))
                .isEmpty();
        assertThat(ids("fromDate", created.plusSeconds(3600).toString())).isEmpty();
        assertThat(ids("fromDate", created.toString(), "toDate", created.toString()))
                .as("an equal start and end is a valid one-instant window").contains(t[0].getId());
    }

    @Test
    @DisplayName("All filters together are AND-ed: only the ticket that matches every one is returned")
    void allFiltersCombined() throws Exception {
        Incident[] t = twoTickets();
        Incident a = t[0];
        Instant created = a.getCreatedAt();
        Set<String> found = ids(
                "status", a.getStatus().name(), "status", "CLOSED",
                "slaStatus", "BREACHED", "slaStatus", "AT_RISK",
                "priority", "P1",
                "category", a.getCategory().name(),
                "serviceId", a.getServiceId(),
                "assigneeId", a.getAssigneeId(),
                "fromDate", created.minusSeconds(60).toString(),
                "toDate", created.plusSeconds(3600).toString());
        assertThat(found).containsExactly(a.getId());

        assertThat(ids("slaStatus", "BREACHED", "status", t[1].getStatus().name()))
                .as("A has the SLA state, B the status: no ticket has both").isEmpty();
    }

    @Test
    @DisplayName("fromDate after toDate is 400 ERR_VALIDATION_FAILED naming fromDate")
    void fromDateAfterToDateIsRefused() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", token)
                        .param("fromDate", "2026-11-02T00:00:00Z")
                        .param("toDate", "2026-11-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("fromDate")));
    }
}
