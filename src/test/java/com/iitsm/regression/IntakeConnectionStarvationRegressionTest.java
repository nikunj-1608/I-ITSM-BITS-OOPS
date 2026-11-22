package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-018: ticket intake opens a nested {@code REQUIRES_NEW} transaction (technician assignment) while it holds a
 * connection, and takes a row lock on the department's ticket counter. With as many simultaneous intakes as pool
 * connections every connection is held by an intake waiting for another and the counter locks time out. On a pool
 * of 3, twelve simultaneous distinct reports used to produce HTTP 500s and multi-second stalls (30 simultaneous
 * reports on the default pool of 10: 6 errors and about 4 s latency).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.connection-timeout=3000"})
@ActiveProfiles("test")
@Tag("regression")
class IntakeConnectionStarvationRegressionTest {

    private static final String PASSWORD = "Password123!";
    private static final String[] DEPARTMENTS = {"Engineering", "Finance", "HR", "Sales", "Operations", "Customer Support", "Legal", "Executive"};
    private static final String[] KEYWORDS = {"router", "keyboard", "postgres", "kubernetes", "okta", "payroll"};
    private static final int REPORTS = 12;

    @Autowired private TestRestTemplate http;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final Random random = new Random(53);

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

    private String reporterToken(int index) {
        String email = "starve." + index + "@nimbusfinserve.com";
        http.postForEntity("/api/auth/register", Map.of("name", "Starve Reporter " + index, "email", email,
                "password", PASSWORD, "department", DEPARTMENTS[index % DEPARTMENTS.length]), Map.class);
        Map<?, ?> login = http.postForEntity("/api/auth/login", Map.of("email", email, "password", PASSWORD), Map.class).getBody();
        return (String) login.get("token");
    }

    private int report(String token, String keyword) {
        StringBuilder description = new StringBuilder("The " + keyword + " and the gateway");
        for (int i = 0; i < 14; i++) {
            description.append(' ').append(word());
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("title", "Fault " + keyword + " " + word() + " " + word(),
                "description", description.append(' ').append(keyword).append('.').toString());
        return http.exchange("/api/employee/incidents", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class)
                .getStatusCode().value();
    }

    @Test
    @DisplayName("Twelve simultaneous distinct reports on a 3-connection pool all create a ticket")
    void simultaneousIntakesDoNotStarveThePool() throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < REPORTS; i++) {
            tokens.add(reporterToken(i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(REPORTS);
        CyclicBarrier barrier = new CyclicBarrier(REPORTS);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < REPORTS; i++) {
            String token = tokens.get(i);
            String keyword = KEYWORDS[i % KEYWORDS.length];
            Callable<Integer> call = () -> {
                barrier.await();
                return report(token, keyword);
            };
            futures.add(pool.submit(call));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        pool.shutdownNow();

        assertThat(statuses).as("every report creates a ticket; none fails or times out").containsOnly(201);
    }
}
