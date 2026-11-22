package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.observer.TransactionAwareDomainEventPublisher;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
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
import org.junit.jupiter.api.extension.RegisterExtension;
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
 * ADR-017 end to end: a notification is sent for a change that committed, exactly once, and never for a change that
 * lost a race and rolled back. Before the fix, every request that passed the state check sent its message before
 * the commit failed (233 "acknowledged" messages for 60 committed acknowledgements in the stress run).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("regression")
class NotificationAfterCommitRegressionTest {

    private static final String PASSWORD = "Password123!";
    private static final String EMPLOYEE = "employee@nimbusfinserve.com";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("no-reply@nimbusfinserve.com", "password"))
            .withPerMethodLifecycle(false);

    @Autowired private TestRestTemplate http;
    @Autowired private DomainEventPublisher eventPublisher;
    @Autowired private IncidentService incidentService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final Random random = new Random(23);

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

    private String technicianToken(Incident ticket) {
        String email = userRepository.findById(ticket.getAssigneeId()).orElseThrow().getEmail().value();
        ResponseEntity<Map> login = http.postForEntity("/api/auth/login", Map.of("email", email, "password", PASSWORD), Map.class);
        return (String) login.getBody().get("token");
    }

    private int acknowledge(String token, String ticketId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/technician/incidents/" + ticketId + "/acknowledge", HttpMethod.POST,
                new HttpEntity<>(headers), Map.class).getStatusCode().value();
    }

    /** Notifications are delivered by a worker thread after the response: wait for it before counting messages. */
    private long acknowledgedMessagesFor(String ticketId) throws Exception {
        assertThat(((TransactionAwareDomainEventPublisher) eventPublisher).awaitIdle(Duration.ofSeconds(10)))
                .as("delivery worker is idle").isTrue();
        long count = 0;
        for (MimeMessage message : greenMail.getReceivedMessages()) {
            if (message.getSubject() != null && message.getSubject().contains("Acknowledged")
                    && (message.getSubject().contains(ticketId) || GreenMailUtil.getBody(message).contains(ticketId))) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("A committed acknowledge sends the reporter exactly one notification (delivery still works)")
    void committedChangeIsAnnouncedOnce() throws Exception {
        Incident ticket = newTicket();

        int status = acknowledge(technicianToken(ticket), ticket.getId());

        assertThat(status).isEqualTo(200);
        assertThat(acknowledgedMessagesFor(ticket.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Ten simultaneous acknowledges send one notification, not one per attempt")
    void lostRacesSendNoNotification() throws Exception {
        for (int run = 0; run < 5; run++) {
            Incident ticket = newTicket();
            String token = technicianToken(ticket);
            ExecutorService pool = Executors.newFixedThreadPool(10);
            CyclicBarrier barrier = new CyclicBarrier(10);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Callable<Integer> call = () -> {
                    barrier.await();
                    return acknowledge(token, ticket.getId());
                };
                futures.add(pool.submit(call));
            }
            int winners = 0;
            for (Future<Integer> future : futures) {
                if (future.get() == 200) {
                    winners++;
                }
            }
            pool.shutdownNow();

            assertThat(winners).as("exactly one acknowledge commits").isEqualTo(1);
            assertThat(acknowledgedMessagesFor(ticket.getId()))
                    .as("one notification for the one committed acknowledgement")
                    .isEqualTo(1);
        }
    }
}
