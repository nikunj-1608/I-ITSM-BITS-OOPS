package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-017 acceptance test over real HTTP: requests that hit the same record at the same instant (released together
 * behind a barrier) must end as 200/201 or a clean 409, never 500. Each scenario is repeated because the outcome of
 * a race is probabilistic; the assertions hold for every possible interleaving.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("regression")
class ConcurrentConflictRegressionTest {

    private static final String PASSWORD = "Password123!";
    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final int REPEATS = 5;
    private static final String CONFLICT = "ERR_CONCURRENT_MODIFICATION";
    private static final String BAD_STATE = "ERR_INVALID_STATE_TRANSITION";

    @Autowired private TestRestTemplate http;
    @Autowired private IncidentService incidentService;
    @Autowired private UserRepository userRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final Random random = new Random(17);

    private record Result(int status, String code) {}

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    private String word() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
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

    private String loginAs(String email) {
        ResponseEntity<Map> response = http.postForEntity("/api/auth/login", Map.of("email", email, "password", PASSWORD), Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("login " + email).isTrue();
        return (String) response.getBody().get("token");
    }

    private String emailOf(String userId) {
        return userRepository.findById(userId).orElseThrow().getEmail().value();
    }

    private Result call(String token, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        Object code = response.getBody() == null ? null : response.getBody().get("code");
        return new Result(response.getStatusCode().value(), code == null ? null : code.toString());
    }

    /** Runs all calls at the same instant and returns their results. */
    private List<Result> race(List<Callable<Result>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CyclicBarrier barrier = new CyclicBarrier(calls.size());
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (Callable<Result> call : calls) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return call.call();
                }));
            }
            List<Result> results = new ArrayList<>();
            for (Future<Result> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertNoServerErrors(List<Result> results, String... allowedConflictCodes) {
        assertThat(results).as("no request may end as a server error").noneMatch(r -> r.status() >= 500);
        assertThat(results.stream().filter(r -> r.status() == 409).map(Result::code))
                .as("every conflict carries a stable code").isSubsetOf(allowedConflictCodes);
    }

    @Test
    @DisplayName("Ten simultaneous acknowledges of one ticket: one winner, nine clean 409s")
    void parallelAcknowledges() throws Exception {
        for (int run = 0; run < REPEATS; run++) {
            Incident ticket = newTicket();
            String token = loginAs(emailOf(ticket.getAssigneeId()));
            List<Callable<Result>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(() -> call(token, "/api/technician/incidents/" + ticket.getId() + "/acknowledge", null));
            }

            List<Result> results = race(calls);

            assertNoServerErrors(results, CONFLICT, BAD_STATE);
            assertThat(results.stream().filter(r -> r.status() == 200)).as("exactly one acknowledge wins").hasSize(1);
        }
    }

    @Test
    @DisplayName("Employee verifies and rejects the same resolution at once: no server error")
    void verifyAgainstReject() throws Exception {
        for (int run = 0; run < REPEATS; run++) {
            Incident ticket = newTicket();
            String technician = ticket.getAssigneeId();
            incidentService.acknowledge(ticket.getId(), technician);
            incidentService.startInvestigation(ticket.getId(), technician);
            incidentService.resolve(ticket.getId(), technician, new Resolution(
                    "Stale tunnel", "Restarted the gateway", "Monitoring", "NETWORK", technician, Instant.now()));
            String token = loginAs(EMPLOYEE);

            List<Result> results = race(List.of(
                    () -> call(token, "/api/employee/incidents/" + ticket.getId() + "/verify", null),
                    () -> call(token, "/api/employee/incidents/" + ticket.getId() + "/reject", Map.of("reason", "still broken"))));

            assertNoServerErrors(results, CONFLICT, BAD_STATE);
            assertThat(results.stream().filter(r -> r.status() == 200)).as("one of the two wins").hasSize(1);
        }
    }

    @Test
    @DisplayName("Five admins reassign one ticket to five technicians at once: no server error")
    void parallelReassignments() throws Exception {
        String admin = loginAs("admin@nimbusfinserve.com");
        for (int run = 0; run < REPEATS; run++) {
            Incident ticket = newTicket();
            List<String> targets = technicianRepository.findAll().stream().map(Technician::getId)
                    .filter(id -> !id.equals(ticket.getAssigneeId())).limit(5).toList();
            List<Callable<Result>> calls = new ArrayList<>();
            for (String target : targets) {
                calls.add(() -> call(admin, "/api/admin/incidents/" + ticket.getId() + "/assign", Map.of("technicianId", target)));
            }

            List<Result> results = race(calls);

            assertNoServerErrors(results, CONFLICT, BAD_STATE, "ERR_TECHNICIAN_OVERLOADED");
            assertThat(results.stream().filter(r -> r.status() == 200)).as("at least one reassignment wins").isNotEmpty();
        }
    }

    /** One token per department: tickets of the same department are serialised by the ticket-number lock. */
    private List<String> reporterTokensFromEightDepartments() {
        String[] departments = {"Engineering", "Finance", "HR", "Sales", "Operations", "Customer Support", "Legal", "Executive"};
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < departments.length; i++) {
            String email = "burst." + i + "@nimbusfinserve.com";
            http.postForEntity("/api/auth/register", Map.of("name", "Burst Reporter " + i, "email", email,
                    "password", PASSWORD, "department", departments[i]), Map.class); // already registered on later runs: ignored
            tokens.add(loginAs(email));
        }
        return tokens;
    }

    @Test
    @DisplayName("Eight identical reports from eight departments at once: every reporter gets a ticket (ADR-018)")
    void identicalReportBurst() throws Exception {
        List<String> reporters = reporterTokensFromEightDepartments();
        for (int run = 0; run < REPEATS; run++) {
            Map<String, String> body = Map.of("title", "Payment gateway checkout outage " + word(), "description",
                    "Checkout on the payment gateway fails for every card payment with HTTP 502, customers cannot "
                            + "complete orders and billing is blocked.");
            call(reporters.get(0), "/api/employee/incidents", body); // the parent every burst report is a duplicate of
            List<Callable<Result>> calls = new ArrayList<>();
            for (String token : reporters) {
                calls.add(() -> call(token, "/api/employee/incidents", body));
            }

            List<Result> results = race(calls);

            assertThat(results).as("intake is serialised, so no report loses the race").allMatch(r -> r.status() == 201);
        }
    }
}
