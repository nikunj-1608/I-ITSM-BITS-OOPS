package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.UnassignedIncidentDispatcher;
import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventListener;
import com.iitsm.domain.model.FactorContribution;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import com.iitsm.testsupport.CapacityInvariant;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Remaining-defects plan, Phase B (ADR-026): a technician's capacity slot must never outlive the work it was taken
 * for. Before the fix {@code TechnicianAssignmentService} committed the slot in a transaction of its own, so any
 * rollback of the caller afterwards left a phantom slot (stress-audit items H8, M6 and the unexplained P2 mismatch).
 *
 * <p>Every scenario makes the caller fail after the slot was taken, then compares the capacity invariant with the
 * state before the scenario (earlier tests in the shared database leave tickets without slots behind, so only new
 * violations count). The failure is injected by a test-only listener that throws when a ticket whose title carries
 * {@link #FAIL_MARKER} is announced as assigned; events are published inside the assigning transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class CapacityAccountingRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String REPORTER_ID = "EMP-DEMO-01";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String FAIL_MARKER = "zzfailassign";
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    /** Throws when a ticket marked for failure is announced as assigned; counts how often it did. */
    static class FailAssignmentOfMarkedTickets implements DomainEventListener {
        private final IncidentRepository incidentRepository;
        final AtomicInteger injectedFailures = new AtomicInteger();

        FailAssignmentOfMarkedTickets(IncidentRepository incidentRepository) {
            this.incidentRepository = incidentRepository;
        }

        @Override
        public void onEvent(DomainEvent event) {
            if (event instanceof DomainEvent.IncidentAssigned assigned
                    && incidentRepository.findById(assigned.incidentId())
                            .map(i -> i.getTitle().contains(FAIL_MARKER)).orElse(false)) {
                injectedFailures.incrementAndGet();
                throw new IllegalStateException("injected failure after the technician slot was taken");
            }
        }
    }

    @TestConfiguration
    static class FailureInjection {
        @Bean
        FailAssignmentOfMarkedTickets failAssignmentOfMarkedTickets(IncidentRepository incidentRepository) {
            return new FailAssignmentOfMarkedTickets(incidentRepository);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private UnassignedIncidentDispatcher dispatcher;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private SupportTeamRepository supportTeamRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FailAssignmentOfMarkedTickets failure;

    /**
     * Static on purpose: JUnit creates a new test instance for every method, so an instance-level generator would
     * start from the same seed each time and every scenario would produce the same "unique" ticket text, which the
     * duplicate detection (correctly) links together.
     */
    private static final Random RANDOM = new Random(29);

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

    private String word() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            sb.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return sb.toString();
    }

    /** A clear, unique VPN ticket: confident enough to be auto-assigned, different enough not to be a duplicate. */
    private Incident newVpnTicket(String titleSuffix) {
        StringBuilder description = new StringBuilder("The vpn and the gateway");
        for (int i = 0; i < 14; i++) {
            description.append(' ').append(word());
        }
        return incidentService.createIncident(EMPLOYEE, "VPN gateway " + word() + " " + titleSuffix,
                description.append(" vpn.").toString());
    }

    private Incident assignedTicket() {
        Incident created = newVpnTicket(word());
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        return created;
    }

    /** A confident, unassigned, non-triage ticket exactly as the dispatcher expects to find it. */
    private Incident waitingTicket(String titleSuffix) {
        Incident incident = new Incident("INC-CAP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "VPN gateway " + word() + " " + titleSuffix, "The vpn gateway drops " + word() + " " + word() + ".",
                REPORTER_ID, NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.40,
                List.of(new FactorContribution("Crit", 0.4, 1.0, 0.4, "test")), List.of(), NOW, null, null);
        incident.classify(IncidentCategory.NETWORK, "SVC-VPN", decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        return incidentRepository.save(incident);
    }

    private Set<String> violations() {
        return CapacityInvariant.check(technicianRepository, incidentRepository).stream()
                .map(v -> v.kind() + "|" + v.technicianId() + "|" + v.incidentId())
                .collect(Collectors.toSet());
    }

    private void assertNoNewViolations(Set<String> before) {
        Set<String> added = new HashSet<>(violations());
        added.removeAll(before);
        assertThat(added).as("capacity violations introduced by the scenario").isEmpty();
    }

    private String adminToken() {
        User admin = userRepository.findById(ADMIN_ID).orElseThrow();
        return "Bearer " + tokenProvider.generateToken(admin);
    }

    private int adminPost(String path, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("B1: when the assignment step of an intake fails, the reporter keeps the ticket and no slot is left behind")
    void failedAssignmentAfterIntakeKeepsTheTicketAndLeaksNoSlot() {
        Set<String> before = violations();
        int failuresBefore = failure.injectedFailures.get();

        Incident created = newVpnTicket(FAIL_MARKER);

        assertThat(failure.injectedFailures.get() - failuresBefore)
                .as("precondition: the assignment was attempted and failed").isEqualTo(1);
        Incident stored = incidentRepository.findById(created.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        assertThat(stored.getAssigneeId()).as("left for the dispatcher").isNull();
        assertThat(stored.isNeedsTriage()).isFalse();
        assertNoNewViolations(before);
    }

    @Test
    @DisplayName("B2 (M6): a reclassify that fails at commit (unknown service id) leaves no slot at the new team")
    void failedReclassifyLeaksNoSlot() throws Exception {
        Incident ticket = assignedTicket();
        SupportTeam securityTeam = supportTeamRepository.findByHandledCategory(IncidentCategory.SECURITY).get(0);
        Assumptions.assumeTrue(!securityTeam.getId().equals(
                technicianRepository.findById(ticket.getAssigneeId()).orElseThrow().getSupportTeamId()));
        Set<String> before = violations();

        int status = adminPost("/api/admin/incidents/" + ticket.getId() + "/reclassify",
                Map.of("category", "SECURITY", "serviceId", "SVC-DOES-NOT-EXIST", "reason", "wrong service"));

        assertThat(status).as("the request must fail").isGreaterThanOrEqualTo(400);
        Incident stored = incidentRepository.findById(ticket.getId()).orElseThrow();
        assertThat(stored.getAssigneeId()).as("assignee unchanged").isEqualTo(ticket.getAssigneeId());
        assertNoNewViolations(before);
    }

    @Test
    @DisplayName("B3 (H8): accept-suggestion on an already assigned ticket is refused and takes no slot")
    void acceptSuggestionOnAssignedTicketLeaksNoSlot() throws Exception {
        Incident ticket = assignedTicket();
        Set<String> before = violations();

        int status = adminPost("/api/admin/triage/" + ticket.getId() + "/accept-suggestion", Map.of("category", "NETWORK"));

        assertThat(status).as("an assigned ticket cannot be assigned again").isEqualTo(409);
        Incident stored = incidentRepository.findById(ticket.getId()).orElseThrow();
        assertThat(stored.getAssigneeId()).isEqualTo(ticket.getAssigneeId());
        assertNoNewViolations(before);
    }

    @Test
    @DisplayName("B4: a dispatcher cycle in which one ticket fails does not lose the others and leaks no slot")
    void failingTicketDoesNotAffectTheRestOfTheDispatcherCycle() {
        Incident ok = waitingTicket(word());
        Incident failing = waitingTicket(FAIL_MARKER);
        Set<String> before = violations();
        int failuresBefore = failure.injectedFailures.get();

        dispatcher.dispatchWaitingIncidents();

        assertThat(failure.injectedFailures.get() - failuresBefore)
                .as("precondition: the failing ticket was attempted").isGreaterThanOrEqualTo(1);
        Incident okStored = incidentRepository.findById(ok.getId()).orElseThrow();
        assertThat(okStored.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(okStored.getAssigneeId()).isNotNull();
        Incident failingStored = incidentRepository.findById(failing.getId()).orElseThrow();
        assertThat(failingStored.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        assertThat(failingStored.getAssigneeId()).isNull();
        assertNoNewViolations(before);
    }
}
