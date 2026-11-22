package com.iitsm.regression;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Remaining-defects plan, Phase I (ADR-038, item R-N): the notification backlog and loss counters are served by the
 * Actuator metrics endpoint, which only administrators may read (the health endpoint stays public for probes).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class ObservabilityEndpointsRegressionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    private String tokenOf(String userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + tokenProvider.generateToken(user);
    }

    @Test
    @DisplayName("R-N: an administrator reads the pending and the dropped notification metrics")
    void administratorReadsTheMetrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics/iitsm.notifications.pending").header("Authorization", tokenOf("ADMIN-SYS-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("iitsm.notifications.pending"));
        mockMvc.perform(get("/actuator/metrics/iitsm.notifications.dropped").header("Authorization", tokenOf("ADMIN-SYS-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("iitsm.notifications.dropped"));
    }

    @Test
    @DisplayName("R-N: the metrics are not public and not for employees or technicians")
    void othersAreRefused() throws Exception {
        mockMvc.perform(get("/actuator/metrics/iitsm.notifications.pending")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics").header("Authorization", tokenOf("EMP-DEMO-01"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics").header("Authorization", tokenOf("TECH-NET-1"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the health endpoint stays public for probes (it answers UP or DOWN, never 401 or 403)")
    void healthStaysPublic() throws Exception {
        int answer = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(answer).isIn(200, 503);
    }
}
