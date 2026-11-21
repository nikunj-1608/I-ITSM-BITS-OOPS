package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.UnassignedIncidentDispatcher;
import com.iitsm.domain.exception.IitsmException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.testsupport.CapacityInvariant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-026 acceptance test for the capacity counter under contention (the "1 of 24 technicians" mismatch the stress
 * audit saw after its race runs). Intakes, technician resolves, administrator reassignments and dispatcher cycles are
 * released together behind a barrier; whatever each one ends as (done, or a clean conflict), every technician's stored
 * slots must match the tickets that name them afterwards. The outcome of a race is probabilistic, so the scenario is
 * repeated; the assertions hold for every possible interleaving.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class CapacityRaceRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final int REPEATS = 3;
    private static final int INTAKES = 10;
    private static final int PREPARED = 6;
    private static final Random RANDOM = new Random(37);

    @Autowired private IncidentService incidentService;
    @Autowired private UnassignedIncidentDispatcher dispatcher;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
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

    private static String word() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            sb.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return sb.toString();
    }

    private Incident newTicket() {
        StringBuilder description = new StringBuilder("The vpn and the gateway");
        for (int i = 0; i < 14; i++) {
            description.append(' ').append(word());
        }
        return incidentService.createIncident(EMPLOYEE, "VPN gateway " + word() + " " + word(),
                description.append(" vpn.").toString());
    }

    private Set<String> violations() {
        return CapacityInvariant.check(technicianRepository, incidentRepository).stream()
                .map(v -> v.kind() + "|" + v.technicianId() + "|" + v.incidentId())
                .collect(Collectors.toSet());
    }

    /** Clean outcomes of a lost race: a conflict, or a business refusal such as "technician is full". */
    private static boolean isCleanFailure(Throwable failure) {
        return failure instanceof IitsmException || failure instanceof ConcurrencyFailureException
                || (failure.getCause() != null && isCleanFailure(failure.getCause()));
    }

    /** Runs every task at the same instant; returns the failures that were not clean conflicts. */
    private List<Throwable> race(List<Callable<Void>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(() -> {
                barrier.await();
                return task.call();
            }));
        }
        List<Throwable> unexpected = new ArrayList<>();
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (java.util.concurrent.ExecutionException e) {
                if (!isCleanFailure(e.getCause())) {
                    unexpected.add(e.getCause());
                }
            }
        }
        pool.shutdown();
        return unexpected;
    }

    @Test
    @DisplayName("Concurrent intakes, resolves, reassignments and dispatcher runs leave every technician's slots matching the tickets")
    void slotsMatchTicketsAfterMixedConcurrentWork() throws Exception {
        for (int round = 0; round < REPEATS; round++) {
            // Every ticket is a network ticket and, since ADR-036, only the network team takes it (no "anyone" fallback),
            // so the slots of the earlier rounds are cleared; differences that exist now are the baseline below
            jdbcTemplate.execute("DELETE FROM technician_active_incidents");
            Set<String> before = violations();
            List<Incident> prepared = new ArrayList<>();
            for (int i = 0; i < PREPARED; i++) {
                Incident ticket = newTicket();
                Assumptions.assumeTrue(ticket.getAssigneeId() != null, "prepared ticket must be auto-assigned");
                prepared.add(ticket);
            }
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < INTAKES; i++) {
                tasks.add(() -> { newTicket(); return null; });
            }
            for (int i = 0; i < PREPARED / 2; i++) {
                Incident toResolve = prepared.get(i);
                tasks.add(() -> {
                    incidentService.acknowledge(toResolve.getId(), toResolve.getAssigneeId());
                    incidentService.startInvestigation(toResolve.getId(), toResolve.getAssigneeId());
                    incidentService.resolve(toResolve.getId(), toResolve.getAssigneeId(), new Resolution(
                            "cause", "steps", "prevention", "NETWORK", toResolve.getAssigneeId(), Instant.now()));
                    return null;
                });
            }
            List<Technician> technicians = technicianRepository.findAll();
            for (int i = PREPARED / 2; i < PREPARED; i++) {
                Incident toMove = prepared.get(i);
                Technician other = technicians.stream()
                        .filter(t -> !t.getId().equals(toMove.getAssigneeId())).findFirst().orElseThrow();
                tasks.add(() -> { incidentService.assign(toMove.getId(), ADMIN_ID, other.getId()); return null; });
            }
            for (int i = 0; i < 3; i++) {
                tasks.add(() -> { dispatcher.dispatchWaitingIncidents(); return null; });
            }

            List<Throwable> unexpected = race(tasks);

            assertThat(unexpected).as("round " + round + ": only clean conflicts are allowed").isEmpty();
            Set<String> added = new HashSet<>(violations());
            added.removeAll(before);
            assertThat(added).as("round " + round + ": capacity violations introduced by the race").isEmpty();
        }
    }
}
