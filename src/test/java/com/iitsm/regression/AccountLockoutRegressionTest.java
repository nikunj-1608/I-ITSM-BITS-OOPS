package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.config.AdjustableClock;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Remaining-defects plan, Phase E (ADR-033, stress-audit item M4, decision D11): the lock set by five failed logins
 * expires after {@code iitsm.security.lockout-minutes} (15), for the login and for the JWT filter, with the same
 * generic message as a wrong password and the same rule for administrators. Before, {@code locked_until} was never
 * written: any stranger could lock any account for good with five bad passwords.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class AccountLockoutRegressionTest {

    private static final String PASSWORD = "Password123!";
    private static final String GENERIC = "Invalid credentials";
    private static final int LOCKOUT_MINUTES = 15;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    private AdjustableClock adjustable;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        Assumptions.assumeTrue(clock instanceof AdjustableClock);
        adjustable = (AdjustableClock) clock;
    }

    @AfterEach
    void tearDown() {
        if (adjustable != null) {
            adjustable.resetToRealTime();
        }
        jdbcTemplate.execute("UPDATE users SET failed_login_attempts = 0, locked = false, locked_until = NULL");
    }

    private String register() throws Exception {
        String email = "lock." + UUID.randomUUID().toString().substring(0, 8) + "@nimbusfinserve.com";
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Lock Test", "email", email,
                                "password", PASSWORD, "department", "Engineering"))))
                .andExpect(status().isOk());
        return email;
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private String token(String email) throws Exception {
        String body = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token));
    }

    private void failFiveTimes(String email) throws Exception {
        for (int i = 0; i < 5; i++) {
            login(email, "Wrong-" + PASSWORD).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("M4: five bad passwords lock the account; the lock holds for 14 minutes and is gone after 15")
    void lockExpiresAfterFifteenMinutes() throws Exception {
        String email = register();
        String token = token(email);
        failFiveTimes(email);

        login(email, PASSWORD).andExpect(status().isUnauthorized());
        me(token).andExpect(status().isUnauthorized());

        adjustable.fastForward(Duration.ofMinutes(LOCKOUT_MINUTES - 1));
        login(email, PASSWORD).andExpect(status().isUnauthorized());
        me(token).andExpect(status().isUnauthorized());

        adjustable.fastForward(Duration.ofMinutes(2));
        login(email, PASSWORD).andExpect(status().isOk());
        me(token).andExpect(status().isOk());

        User after = userRepository.findByEmail(new Email(email)).orElseThrow();
        assertThat(after.isLocked()).as("the successful login cleared the stored lock").isFalse();
        assertThat(after.getLockedUntil()).isNull();
        assertThat(after.getFailedLoginAttempts()).isZero();
    }

    @Test
    @DisplayName("M4: the JWT filter accepts a token again after the lock expired, even before the next login")
    void filterAcceptsTheUserAfterExpiry() throws Exception {
        String email = register();
        String token = token(email);
        failFiveTimes(email);
        me(token).andExpect(status().isUnauthorized());

        adjustable.fastForward(Duration.ofMinutes(LOCKOUT_MINUTES + 1));

        me(token).andExpect(status().isOk());
    }

    @Test
    @DisplayName("M4: after the lock expired one wrong password does not lock the account again")
    void oneWrongPasswordAfterExpiryDoesNotRelock() throws Exception {
        String email = register();
        failFiveTimes(email);
        adjustable.fastForward(Duration.ofMinutes(LOCKOUT_MINUTES + 1));

        login(email, "Wrong-" + PASSWORD).andExpect(status().isUnauthorized());

        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("M4: a login attempt inside the lock does not extend it")
    void attemptsInsideTheLockDoNotExtendIt() throws Exception {
        String email = register();
        failFiveTimes(email);
        adjustable.fastForward(Duration.ofMinutes(LOCKOUT_MINUTES - 5));
        for (int i = 0; i < 3; i++) {
            login(email, "Wrong-" + PASSWORD).andExpect(status().isUnauthorized());
        }

        adjustable.fastForward(Duration.ofMinutes(6));

        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("D11: a locked account, a wrong password and an unknown user get the same answer")
    void sameGenericMessage() throws Exception {
        String email = register();
        failFiveTimes(email);

        login(email, PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(GENERIC));
        login(email, "Wrong-" + PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(GENERIC));
        login("nobody." + UUID.randomUUID() + "@nimbusfinserve.com", PASSWORD)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(GENERIC));
    }

    @Test
    @DisplayName("D11: administrators are locked and unlocked by the same rule")
    void administratorsFollowTheSameRule() throws Exception {
        String admin = "admin@nimbusfinserve.com";
        failFiveTimes(admin);
        login(admin, PASSWORD).andExpect(status().isUnauthorized());

        adjustable.fastForward(Duration.ofMinutes(LOCKOUT_MINUTES + 1));

        login(admin, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("M4: the technician roster shows a lock only while it lasts")
    void rosterShowsTheLockOnlyWhileItLasts() throws Exception {
        String adminToken = token("admin@nimbusfinserve.com");
        String technician = "tech.net3@nimbusfinserve.com";
        failFiveTimes(technician);

        mockMvc.perform(get("/api/admin/technicians").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email=='" + technician + "')].locked").value(true));

        adjustable.fastForward(Duration.ofMinutes(LOCKOUT_MINUTES + 1));

        mockMvc.perform(get("/api/admin/technicians").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email=='" + technician + "')].locked").value(false));
    }
}
