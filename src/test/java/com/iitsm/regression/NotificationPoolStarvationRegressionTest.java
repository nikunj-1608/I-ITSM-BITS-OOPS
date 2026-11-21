package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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
 * ADR-017 amendment: delivering notifications after commit must never wait for a database connection while the
 * request still holds its own. With a pool of 3 connections, 8 simultaneous successful writes each used to hold a
 * connection while their after-commit notification asked for a second one: every connection was held by a thread
 * waiting for another, and the whole application stalled until the pool timeout (3 s here, 30 s by default).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.connection-timeout=3000"})
@ActiveProfiles("test")
@Tag("regression")
class NotificationPoolStarvationRegressionTest {

    private static final String PASSWORD = "Password123!";
    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final int WRITERS = 8;
    /** Well below the 3 s pool timeout: a stalled pool cannot finish this fast. */
    private static final long MAX_REQUEST_MILLIS = 2500;

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("no-reply@nimbusfinserve.com", "password"))
            .withPerMethodLifecycle(false);

    @Autowired private TestRestTemplate http;
    @Autowired private IncidentService incidentService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final Random random = new Random(41);

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

    private String tokenFor(String userId) {
        String email = userRepository.findById(userId).orElseThrow().getEmail().value();
        Map<?, ?> body = http.postForEntity("/api/auth/login", Map.of("email", email, "password", PASSWORD), Map.class).getBody();
        return (String) body.get("token");
    }

    private int acknowledge(String token, String ticketId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/technician/incidents/" + ticketId + "/acknowledge", HttpMethod.POST,
                new HttpEntity<>(headers), Map.class).getStatusCode().value();
    }

    private Set<String> ticketsWithAcknowledgedMail() throws Exception {
        Set<String> tickets = new HashSet<>();
        for (MimeMessage message : greenMail.getReceivedMessages()) {
            if (message.getSubject() != null && message.getSubject().contains("Acknowledged")) {
                String text = message.getSubject() + " " + GreenMailUtil.getBody(message);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Z]{2,4}-\\d{4}-\\d{6}").matcher(text);
                while (m.find()) {
                    tickets.add(m.group());
                }
            }
        }
        return tickets;
    }

    @Test
    @DisplayName("Eight simultaneous successful writes on a 3-connection pool all finish fast and all notify")
    void concurrentCommitsDoNotStarveThePool() throws Exception {
        List<Incident> tickets = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            Incident ticket = newTicket();
            tickets.add(ticket);
            tokens.add(tokenFor(ticket.getAssigneeId()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        CyclicBarrier barrier = new CyclicBarrier(WRITERS);
        List<Future<long[]>> futures = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            String token = tokens.get(i);
            String ticketId = tickets.get(i).getId();
            Callable<long[]> call = () -> {
                barrier.await();
                long start = System.currentTimeMillis();
                int status = acknowledge(token, ticketId);
                return new long[] {status, System.currentTimeMillis() - start};
            };
            futures.add(pool.submit(call));
        }
        long slowest = 0;
        for (Future<long[]> future : futures) {
            long[] result = future.get();
            assertThat(result[0]).as("every independent write succeeds").isEqualTo(200);
            slowest = Math.max(slowest, result[1]);
        }
        pool.shutdownNow();

        assertThat(slowest).as("no request waits for the pool timeout (a stalled pool takes at least 3000 ms)")
                .isLessThan(MAX_REQUEST_MILLIS);
        Set<String> expected = new HashSet<>();
        tickets.forEach(t -> expected.add(t.getId()));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                assertThat(ticketsWithAcknowledgedMail()).as("every committed change is announced").containsAll(expected));
    }
}
