package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.config.AdjustableClock;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Authentication hardening, rule R1 (ADR-047): token expiry, refresh expiry and the session check use the real wall
 * clock. {@code /api/simulation/advance} moves the shared simulation clock, by up to ten years; if sessions followed
 * it, one simulation scenario would sign everybody out. Pins the fix of the clock choice, not only the current code.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class SimulationClockDoesNotExpireSessionsRegressionTest {

    private static final String PASSWORD = "Password123!";
    private static final String ADMIN = "admin@nimbusfinserve.com";
    private static final long ONE_DAY_MINUTES = 24 * 60;
    private static final long FIVE_YEARS_MINUTES = 5L * 365 * ONE_DAY_MINUTES;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private Clock sharedClock;
    @Autowired @Qualifier("wallClock") private Clock wallClock;

    private AdjustableClock simulation;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        Assumptions.assumeTrue(sharedClock instanceof AdjustableClock);
        simulation = (AdjustableClock) sharedClock;
    }

    @AfterEach
    void tearDown() {
        if (simulation != null) {
            simulation.resetToRealTime();
        }
    }

    private JsonNode login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", ADMIN, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void advance(String accessToken, long minutes) throws Exception {
        mockMvc.perform(post("/api/simulation/advance?minutes=" + minutes)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("R1: the wall clock bean is separate from the simulation clock, and the simulation clock stays the primary one")
    void twoClocks() {
        assertThat(wallClock).isNotSameAs(sharedClock);
        assertThat(wallClock).isNotInstanceOf(AdjustableClock.class);
        assertThat(sharedClock).isInstanceOf(AdjustableClock.class);
    }

    @Test
    @DisplayName("R1: after the simulation clock advances a day, the access token, the session and the refresh token still work")
    void aDayInTheSimulationDoesNotEndTheSession() throws Exception {
        JsonNode session = login();
        String access = session.get("token").asText();
        String refresh = session.get("refreshToken").asText();

        advance(access, ONE_DAY_MINUTES);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        MvcResult renewed = mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk()).andReturn();
        String newAccess = objectMapper.readTree(renewed.getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + newAccess)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("R1: even five years in the simulation do not expire a token issued before, nor refuse a new sign-in")
    void fiveYearsInTheSimulation() throws Exception {
        JsonNode session = login();
        String access = session.get("token").asText();

        advance(access, FIVE_YEARS_MINUTES);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        String fresh = login().get("token").asText();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + fresh)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("R1: a token issued while the simulation runs ahead is valid against the real time")
    void tokenIssuedInTheFutureOfTheSimulation() throws Exception {
        String admin = login().get("token").asText();
        advance(admin, ONE_DAY_MINUTES);

        JsonNode inSimulatedFuture = login();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + inSimulatedFuture.get("token").asText()))
                .andExpect(status().isOk());
        simulation.resetToRealTime();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + inSimulatedFuture.get("token").asText()))
                .andExpect(status().isOk());
    }
}
