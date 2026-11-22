package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.application.service.AdminIncidentService;
import com.iitsm.application.service.AssignmentDecisionRecorder;
import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.TicketDispatchService;
import com.iitsm.application.service.UnassignedIncidentDispatcher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.ReassignmentRequest;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.AssignmentDecisionRepository;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.assignment.AssignmentDecision;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.domain.strategy.assignment.CandidateScore;
import com.iitsm.domain.strategy.assignment.PoolKind;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-044, Phase L2: every way a ticket gets its technician records exactly one assignment decision, in the same
 * transaction as the assignment. Like {@code TicketRoutingContractTest}, one expectation runs through every caller:
 * the decision names the technician the ticket now has; a strategy's decision starts its ranking with that technician
 * and ranks only technicians of the pool it names; the other kinds carry no ranking and name the right actor.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class AssignmentExplanationContractTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String REPORTER_ID = "EMP-DEMO-01";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String GENERAL_SERVICE = "SRV-GEN-001";
    private static final String OVERFLOW_SKILL = "Explanation overflow test";
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** Every way a ticket gets its assignee. */
    enum Path { INTAKE, DISPATCHER, RECLASSIFY, ACCEPT_SUGGESTION, MANUAL, INHERITED_FROM_PARENT, REQUEUED }

    @Autowired private IncidentService incidentService;
    @Autowired private AdminIncidentService adminService;
    @Autowired private TicketDispatchService dispatchService;
    @Autowired private UnassignedIncidentDispatcher dispatcher;
    @Autowired private AssignmentDecisionRecorder recorder;
    @Autowired private AssignmentDecisionRepository decisions;
    @Autowired private AssignmentStrategyRegistry strategyRegistry;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private SupportTeamRepository supportTeamRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private final List<String> createdTickets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        // Open tickets with the same text from other classes would turn the reported tickets into their duplicates
        jdbcTemplate.update("UPDATE incidents SET status = 'CLOSED' WHERE title = ? AND status <> 'CLOSED'",
                "VPN gateway timeouts for the whole office");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM technician_skills WHERE skill_name = ?", OVERFLOW_SKILL);
        // Open tickets of this class must neither become duplicate parents nor be picked up by a later dispatcher run
        createdTickets.forEach(id -> jdbcTemplate.update("UPDATE incidents SET status = 'CLOSED' WHERE id = ?", id));
        jdbcTemplate.update("UPDATE incidents SET status = 'CLOSED' WHERE id LIKE 'TST-EXPL-%'");
        createdTickets.clear();
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private Incident planted(IncidentCategory category, boolean triage) {
        Incident incident = new Incident("TST-EXPL-" + System.nanoTime() + "-" + COUNTER.incrementAndGet(),
                "Explanation contract " + category, "A ticket planted to test the assignment explanation, " + category + ".",
                REPORTER_ID, NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null);
        incident.classify(category, GENERAL_SERVICE, decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        incident.setNeedsTriage(triage);
        return incidentRepository.save(incident);
    }

    private Incident reported(String title, String description) {
        Incident created = incidentService.createIncident(EMPLOYEE, title, description);
        createdTickets.add(created.getId());
        return load(created.getId());
    }

    private Incident load(String id) {
        return incidentRepository.findById(id).orElseThrow();
    }

    private List<AssignmentDecision> decisionsOf(String ticketId) {
        return decisions.findNewestByIncidentId(ticketId, 50);
    }

    private String teamHandling(IncidentCategory category) {
        return supportTeamRepository.findByHandledCategory(category).stream().map(SupportTeam::getId).findFirst().orElseThrow();
    }

    private String teamOf(String technicianId) {
        return technicianRepository.findById(technicianId).orElseThrow().getSupportTeamId();
    }

    /** Runs the path; returns the ticket and how many decisions it had before the path's own step. */
    private record Outcome(Incident ticket, int decisionsBefore) {}

    private Outcome run(Path path) {
        switch (path) {
            case INTAKE: {
                Incident ticket = planted(IncidentCategory.NETWORK, false);
                dispatchService.dispatchOne(ticket.getId(), "SYSTEM");
                return new Outcome(load(ticket.getId()), 0);
            }
            case DISPATCHER: {
                Incident ticket = planted(IncidentCategory.DATABASE, false);
                dispatcher.dispatchWaitingIncidents();
                return new Outcome(load(ticket.getId()), 0);
            }
            case RECLASSIFY: {
                Incident created = reported("VPN gateway timeouts for the whole office",
                        "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
                int before = decisionsOf(created.getId()).size();
                adminService.reclassify(created.getId(), ADMIN_ID, IncidentCategory.DATABASE, null, "Wrong domain");
                return new Outcome(load(created.getId()), before);
            }
            case ACCEPT_SUGGESTION: {
                Incident triage = planted(IncidentCategory.APPLICATION, true);
                adminService.acceptTriageSuggestion(triage.getId(), ADMIN_ID, IncidentCategory.CLOUD, null);
                return new Outcome(load(triage.getId()), 0);
            }
            case MANUAL: {
                Incident ticket = planted(IncidentCategory.SECURITY, true);
                adminService.assign(ticket.getId(), ADMIN_ID, "TECH-NET-2");
                return new Outcome(load(ticket.getId()), 0);
            }
            case INHERITED_FROM_PARENT: {
                String marker = " Office " + COUNTER.incrementAndGet() + " reports it.";
                reported("VPN gateway timeouts for the whole office",
                        "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel." + marker);
                Incident duplicate = reported("VPN gateway timeouts for the whole office",
                        "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel." + marker);
                return new Outcome(duplicate, 0);
            }
            case REQUEUED: {
                Incident ticket = planted(IncidentCategory.NETWORK, false);
                dispatchService.dispatchOne(ticket.getId(), "SYSTEM");
                String holder = load(ticket.getId()).getAssigneeId();
                ReassignmentRequest request = incidentService.flagWrongDomain(ticket.getId(), holder, "Not my area");
                int before = decisionsOf(ticket.getId()).size();
                adminService.rejectReassignment(request.getId(), ADMIN_ID, "It is your area");
                return new Outcome(load(ticket.getId()), before);
            }
            default:
                throw new IllegalStateException();
        }
    }

    // ---- the contract, through every path ----------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(Path.class)
    @DisplayName("ADR-044 contract: the path records one decision that names the ticket's technician")
    void everyPathRecordsOneDecision(Path path) {
        Outcome outcome = run(path);
        Incident ticket = outcome.ticket();
        List<AssignmentDecision> recorded = decisionsOf(ticket.getId());

        assertThat(ticket.getAssigneeId()).as("%s gave the ticket a technician", path).isNotNull();
        assertThat(recorded).as("%s added exactly one decision", path).hasSize(outcome.decisionsBefore() + 1);
        AssignmentDecision newest = recorded.get(0);
        assertThat(newest.incidentId()).isEqualTo(ticket.getId());
        assertThat(newest.chosenTechnicianId()).isEqualTo(ticket.getAssigneeId());
        assertThat(newest.poolKind()).isEqualTo(expectedPool(path));
        if (newest.poolKind().isRankedByStrategy()) {
            assertRankedByTheActiveStrategy(newest, ticket);
        } else {
            assertThat(newest.strategyName()).isNull();
            assertThat(newest.candidates()).isEmpty();
        }
        assertThat(newest.actorId()).isEqualTo(expectedActor(path));
    }

    private void assertRankedByTheActiveStrategy(AssignmentDecision decision, Incident ticket) {
        assertThat(decision.strategyName()).isEqualTo(strategyRegistry.getActiveStrategyName());
        assertThat(decision.candidates()).isNotEmpty();
        CandidateScore top = decision.candidates().get(0);
        assertThat(top.chosen()).isTrue();
        assertThat(top.technicianId()).isEqualTo(ticket.getAssigneeId());
        String owningTeam = teamHandling(ticket.getCategory());
        assertThat(decision.candidates()).extracting(c -> teamOf(c.technicianId())).containsOnly(owningTeam);
        assertThat(decision.candidateCount()).isGreaterThanOrEqualTo(decision.candidates().size());
        assertThat(top.factorScores()).isNotEmpty();
    }

    private static PoolKind expectedPool(Path path) {
        return switch (path) {
            case MANUAL -> PoolKind.MANUAL;
            case INHERITED_FROM_PARENT -> PoolKind.INHERITED_FROM_PARENT;
            case REQUEUED -> PoolKind.REQUEUED_TO_SAME_TECHNICIAN;
            default -> PoolKind.OWNING_TEAM;
        };
    }

    private static String expectedActor(Path path) {
        return switch (path) {
            case INTAKE, INHERITED_FROM_PARENT -> "SYSTEM";
            case DISPATCHER -> "DISPATCHER";
            default -> ADMIN_ID;
        };
    }

    // ---- the details of single paths ---------------------------------------------------------------------------

    @Test
    @DisplayName("a duplicate's decision names the parent whose technician it rides")
    void inheritedDecisionNamesTheParent() {
        Incident duplicate = run(Path.INHERITED_FROM_PARENT).ticket();
        AssignmentDecision decision = decisionsOf(duplicate.getId()).get(0);

        Incident parent = load(decision.relatedIncidentId());
        assertThat(parent.getLinkedIncidentIds()).contains(duplicate.getId());
        assertThat(parent.getAssigneeId()).isEqualTo(decision.chosenTechnicianId());
    }

    @Test
    @DisplayName("with the owning team full the decision says SKILLED_OVERFLOW and ranks only the skilled outsiders")
    void skilledOverflowIsRecordedAsSuch() {
        for (Technician t : technicianRepository.findBySupportTeamId(teamHandling(IncidentCategory.NETWORK))) {
            for (int slot = 0; slot < t.getMaxCapacity(); slot++) {
                t.assignIncident("DUMMY-SLOT-" + slot);
            }
            technicianRepository.save(t);
        }
        Technician outsider = technicianRepository.findById("TECH-SEC-1").orElseThrow();
        outsider.addSkill(new Skill(OVERFLOW_SKILL, IncidentCategory.NETWORK));
        technicianRepository.save(outsider);
        Incident ticket = planted(IncidentCategory.NETWORK, false);

        dispatchService.dispatchOne(ticket.getId(), "SYSTEM");

        AssignmentDecision decision = decisionsOf(ticket.getId()).get(0);
        assertThat(decision.poolKind()).isEqualTo(PoolKind.SKILLED_OVERFLOW);
        assertThat(decision.chosenTechnicianId()).isEqualTo("TECH-SEC-1");
        assertThat(decision.candidates()).extracting(CandidateScore::technicianId).containsExactly("TECH-SEC-1");
    }

    @Test
    @DisplayName("a rolled-back assignment leaves neither a slot nor a decision (same transaction, ADR-026)")
    void rollbackLeavesNoDecision() {
        Incident ticket = planted(IncidentCategory.NETWORK, false);

        transactionTemplate.executeWithoutResult(status -> {
            dispatchService.dispatchOne(ticket.getId(), "SYSTEM");
            assertThat(decisionsOf(ticket.getId())).hasSize(1);
            status.setRollbackOnly();
        });

        assertThat(decisionsOf(ticket.getId())).isEmpty();
        assertThat(load(ticket.getId()).getAssigneeId()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM technician_active_incidents WHERE incident_id = ?",
                Integer.class, ticket.getId())).isZero();
    }

    @Test
    @DisplayName("the recorder refuses to run outside a transaction (propagation MANDATORY)")
    void recorderNeedsTheCallersTransaction() {
        Incident ticket = planted(IncidentCategory.NETWORK, false);

        assertThatThrownBy(() -> recorder.recordManual(ticket.getId(), "TECH-NET-1", ADMIN_ID, NOW))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(decisionsOf(ticket.getId())).isEmpty();
    }

    @Test
    @DisplayName("a ticket that waits records no decision")
    void waitingTicketRecordsNothing() {
        for (Technician t : technicianRepository.findBySupportTeamId(teamHandling(IncidentCategory.NETWORK))) {
            for (int slot = 0; slot < t.getMaxCapacity(); slot++) {
                t.assignIncident("DUMMY-SLOT-" + slot);
            }
            technicianRepository.save(t);
        }
        Incident ticket = planted(IncidentCategory.NETWORK, false);

        assertThat(dispatchService.dispatchOne(ticket.getId(), "SYSTEM").outcome())
                .isEqualTo(TicketDispatchService.Outcome.NOT_ASSIGNED);
        assertThat(decisionsOf(ticket.getId())).isEmpty();
    }
}
