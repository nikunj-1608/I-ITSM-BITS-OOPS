package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Remaining-defects plan, Phase E (ADR-033, stress-audit item P1, login half): {@code UserService.authenticate} was one
 * transaction, so it held a connection while {@code LoginAttemptService} asked for a second one in a {@code REQUIRES_NEW}
 * transaction to record a failed attempt. With as many simultaneous logins as pool connections every connection was
 * held by a login waiting for another, and the requests timed out into HTTP 500 (the same shape as the intake
 * starvation of ADR-018). The pool is 3 connections and the wait for a connection 3 seconds, so a starved request
 * ends as a 500 after 3 seconds instead of an answer. The logins use {@code java.net.http.HttpClient}: the JDK's
 * {@code HttpURLConnection} behind {@code TestRestTemplate} throws on a 401 answer to a POST instead of returning it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.connection-timeout=3000"})
@ActiveProfiles("test")
@Tag("regression")
class LoginPoolStarvationRegressionTest {

    private static final String PASSWORD = "Password123!";
    private static final int LOGINS = 12;

    @Autowired private TestRestTemplate http;
    @LocalServerPort private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private String newUser() {
        String email = "login." + UUID.randomUUID().toString().substring(0, 8) + "@nimbusfinserve.com";
        http.postForEntity("/api/auth/register",
                Map.of("name", "Login Burst", "email", email, "password", PASSWORD, "department", "Engineering"), Map.class);
        return email;
    }

    private int login(String email, String password) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    @DisplayName("P1: twelve simultaneous logins, half with a wrong password, on a 3-connection pool are all answered")
    void simultaneousLoginsDoNotStarveThePool() throws Exception {
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < LOGINS; i++) {
            emails.add(newUser());
        }
        ExecutorService pool = Executors.newFixedThreadPool(LOGINS);
        CyclicBarrier barrier = new CyclicBarrier(LOGINS);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < LOGINS; i++) {
            String email = emails.get(i);
            String password = i % 2 == 0 ? PASSWORD : "Wrong-" + PASSWORD;
            Callable<Integer> call = () -> {
                barrier.await();
                return login(email, password);
            };
            futures.add(pool.submit(call));
        }
        long started = System.nanoTime();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        pool.shutdownNow();

        assertThat(statuses).as("a good password gives 200, a wrong one 401; never a 500 from a starved pool")
                .containsOnly(200, 401);
        assertThat(statuses.stream().filter(status -> status == 200).count()).isEqualTo(LOGINS / 2);
        assertThat(seconds).as("no request waited for a connection until the 3 s timeout").isLessThan(3);
    }
}
