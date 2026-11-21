package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The reason for the knowledge base (ADR-042): with a ticket window of one, a fix resolved before the newest ticket of
 * its category is no longer compared, but its article still is, because articles have their own window.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "iitsm.suggestions.candidate-window=1")
@Tag("regression")
class KnowledgeBaseWindowRegressionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private KnowledgeBaseTestSupport kb;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        kb = new KnowledgeBaseTestSupport(mockMvc, objectMapper, tokenProvider, incidentService, userRepository, jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        kb.cleanUp();
    }

    private String resolvedVpn() throws Exception {
        return kb.resolved("VPN gateway rejects every AnyConnect login",
                "Remote staff cannot connect: the AnyConnect VPN gateway refuses the tunnel with a certificate error.",
                "Expired TLS certificate on the AnyConnect VPN gateway",
                "Renewed the gateway certificate and restarted the VPN tunnel service");
    }

    @Test
    @DisplayName("A outside the one-ticket window: B still gets A's article, not A")
    void articleOutlivesTheWindow() throws Exception {
        String a = resolvedVpn();
        String b = kb.ticketWithTechnician("AnyConnect VPN tunnel fails with certificate error",
                "Since this morning the VPN gateway refuses my AnyConnect tunnel and shows a certificate warning.");
        assertThat(KnowledgeBaseTestSupport.find(kb.suggestions(b, KnowledgeBaseTestSupport.TECHNICIAN_ID), a))
                .as("A is the newest network fix, so it is in the window").isNotNull();

        kb.promote(KnowledgeBaseTestSupport.TECHNICIAN_ID, a, null).andExpect(status().isCreated());
        String newer = kb.resolved("Wifi access point drops on floor 3",
                "Laptops on the third floor lose the wireless network and reconnect again and again.",
                "Two access points on the same wifi channel", "Moved one access point to another channel");
        // the newest resolution of the category, whatever other test classes left behind
        jdbcTemplate.update("UPDATE resolutions SET resolved_at = TIMESTAMP '2099-12-31 00:00:00' WHERE incident_id = ?", newer);

        JsonNode list = kb.suggestions(b, KnowledgeBaseTestSupport.TECHNICIAN_ID);

        assertThat(KnowledgeBaseTestSupport.find(list, a)).as("A left the window: " + list).isNull();
        assertThat(KnowledgeBaseTestSupport.find(list, "KB-" + a)).as("A's article is still suggested: " + list).isNotNull();
    }
}
