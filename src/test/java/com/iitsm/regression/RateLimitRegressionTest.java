package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.UserService;
import com.iitsm.domain.model.Designation;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Authentication hardening, Phase S2 (ADR-046): the per-address limit on the sign-in endpoints, over HTTP through the
 * whole security chain. Capacity 3, refilled over 3 seconds (one attempt per second once used up). Each test uses its
 * own client addresses and its own user, so the tests do not share buckets or counters.
 */
@SpringBootTest(properties = {
        "iitsm.security.rate-limit.enabled=true",
        "iitsm.security.rate-limit.login-capacity=3",
        "iitsm.security.rate-limit.login-window-seconds=3"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class RateLimitRegressionTest {

    private static final String PASSWORD = "Password123!";
    private static final int CAPACITY = 3;
    private static final long MILLIS_PER_SECOND = 1000;
    private static final long SLACK_MILLIS = 150;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private Clock clock;

    private String newUser() {
        String email = "rate." + UUID.randomUUID().toString().substring(0, 8) + "@nimbusfinserve.com";
        userService.registerEmployee("Rate Limit", email, PASSWORD, Designation.ASSOCIATE, "Engineering", 1);
        return email;
    }

    private ResultActions login(String ip, String email, String password, String... forwardedFor) throws Exception {
        var request = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password)))
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
        for (String value : forwardedFor) {
            request.header("X-Forwarded-For", value);
        }
        return mockMvc.perform(request);
    }

    private User reload(String email) {
        return userRepository.findByEmail(new Email(email)).orElseThrow();
    }

    @Test
    @DisplayName("S2: the 4th login from one address is 429 with Retry-After; another address still signs in")
    void fourthLoginIsRefused() throws Exception {
        String email = newUser();
        for (int i = 0; i < CAPACITY; i++) {
            login("10.46.0.1", email, PASSWORD).andExpect(status().isOk());
        }

        login("10.46.0.1", email, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("ERR_RATE_LIMITED"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.token").doesNotExist());
        login("10.46.0.2", email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("S2: refused attempts never reach the login: no failure is counted and the account never locks")
    void refusalsAreNotFailedLogins() throws Exception {
        String email = newUser();
        for (int i = 0; i < CAPACITY; i++) {
            login("10.46.0.3", email, "Wrong-" + PASSWORD).andExpect(status().isUnauthorized());
        }
        for (int i = 0; i < 10; i++) {
            login("10.46.0.3", email, "Wrong-" + PASSWORD).andExpect(status().isTooManyRequests());
        }

        User user = reload(email);
        assertThat(user.getFailedLoginAttempts()).isEqualTo(CAPACITY);
        assertThat(user.isCurrentlyLocked(clock.instant())).isFalse();
        login("10.46.0.4", email, PASSWORD).andExpect(status().isOk());
        assertThat(reload(email).getFailedLoginAttempts()).isZero();
    }

    @Test
    @DisplayName("S2: a forged X-Forwarded-For does not give a refused client a fresh bucket (no trusted proxy)")
    void forgedForwardedForIsIgnored() throws Exception {
        String email = newUser();
        for (int i = 0; i < CAPACITY; i++) {
            login("10.46.0.5", email, PASSWORD, "203.0.113." + i).andExpect(status().isOk());
        }

        login("10.46.0.5", email, PASSWORD, "203.0.113.99").andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("S2: after waiting Retry-After seconds a valid login works again")
    void loginWorksAfterRetryAfter() throws Exception {
        String email = newUser();
        for (int i = 0; i < CAPACITY; i++) {
            login("10.46.0.6", email, PASSWORD).andExpect(status().isOk());
        }
        String retryAfter = login("10.46.0.6", email, PASSWORD).andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getHeader("Retry-After");
        long seconds = Long.parseLong(retryAfter);
        assertThat(seconds).isBetween(1L, 3L);

        Thread.sleep(seconds * MILLIS_PER_SECOND + SLACK_MILLIS);

        login("10.46.0.6", email, PASSWORD).andExpect(status().isOk());
    }
}
