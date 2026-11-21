package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * Knowledge base, Phase K3 (ADR-042), end to end over HTTP: who may promote which ticket, the stable codes of every
 * refusal, that no personal data of the reporter reaches an article, and that an article is suggested (once, instead of
 * its ticket) and can be voted on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class KnowledgeBaseRegressionTest {

    private static final String TEAM_LEAD_ID = "LEAD-NET";
    private static final String OTHER_TEAM_TECHNICIAN_ID = "TECH-DB-1";
    private static final String URL = "/api/technician/knowledge-articles";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private KnowledgeBaseTestSupport kb;
    private User reporter;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        kb = new KnowledgeBaseTestSupport(mockMvc, objectMapper, tokenProvider, incidentService, userRepository, jdbcTemplate);
        reporter = userRepository.findById(KnowledgeBaseTestSupport.EMPLOYEE_ID).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        kb.cleanUp();
    }

    /** A resolved VPN ticket whose texts mention the reporter's name, e-mail and department. */
    private String resolvedWithPersonalData() throws Exception {
        return kb.resolved("VPN gateway rejects AnyConnect for " + reporter.getName(),
                "Hello, this is " + reporter.getName() + " (" + reporter.getEmail().value() + ", " + reporter.getDepartment()
                        + "). The AnyConnect VPN gateway refuses my tunnel with a certificate error.",
                "Expired TLS certificate on the AnyConnect VPN gateway, reported by " + reporter.getName(),
                "Renewed the gateway certificate and restarted the VPN tunnel service; told " + reporter.getEmail().value());
    }

    @Test
    @DisplayName("The resolver promotes: 201, id KB-<ticket>, technical content only, no name, e-mail or department")
    void resolverPromotesWithoutPersonalData() throws Exception {
        String ticket = resolvedWithPersonalData();

        String json = kb.promote(KnowledgeBaseTestSupport.TECHNICIAN_ID, ticket, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("KB-" + ticket))
                .andExpect(jsonPath("$.sourceIncidentId").value(ticket))
                .andExpect(jsonPath("$.category").value("NETWORK"))
                .andExpect(jsonPath("$.createdBy").value(KnowledgeBaseTestSupport.TECHNICIAN_ID))
                .andReturn().getResponse().getContentAsString();

        String lower = json.toLowerCase();
        assertThat(lower).contains("anyconnect vpn gateway").contains("renewed the gateway certificate");
        for (String part : reporter.getName().split("\\s+")) {
            if (part.length() >= 3) {
                assertThat(lower).doesNotContain(part.toLowerCase());
            }
        }
        assertThat(lower).doesNotContain(reporter.getEmail().value().toLowerCase())
                .doesNotContain(reporter.getDepartment().toLowerCase())
                .doesNotContain("reporterid").doesNotContain(reporter.getId().toLowerCase());
        String stored = jdbcTemplate.queryForObject("SELECT CONCAT(title, problem, root_cause, steps_taken) FROM knowledge_articles "
                + "WHERE source_incident_id = ?", String.class, ticket);
        assertThat(stored.toLowerCase()).doesNotContain(reporter.getEmail().value().toLowerCase());
    }

    @Test
    @DisplayName("A ticket is promoted once: the second promotion is 409 ERR_ALREADY_PROMOTED")
    void secondPromotionRefused() throws Exception {
        String ticket = resolvedWithPersonalData();
        kb.promote(KnowledgeBaseTestSupport.TECHNICIAN_ID, ticket, "Renew the VPN certificate").andExpect(status().isCreated());

        kb.promote(KnowledgeBaseTestSupport.ADMIN_ID, ticket, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ERR_ALREADY_PROMOTED"));
    }

    @Test
    @DisplayName("Team lead and administrator may promote; an open ticket is 409 ERR_NOT_RESOLVED")
    void leadsAdminsAndOpenTickets() throws Exception {
        String first = resolvedWithPersonalData();
        String second = resolvedWithPersonalData();
        String open = kb.ticketWithTechnician("AnyConnect VPN drops at the gateway",
                "The VPN gateway drops my AnyConnect tunnel every few minutes from home.");

        kb.promote(TEAM_LEAD_ID, first, null).andExpect(status().isCreated());
        kb.promote(KnowledgeBaseTestSupport.ADMIN_ID, second, null).andExpect(status().isCreated());
        kb.promote(KnowledgeBaseTestSupport.ADMIN_ID, open, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ERR_NOT_RESOLVED"));
    }

    @Test
    @DisplayName("Refusals: other team 403, employee 403, no token 401, unknown ticket 404, bad bodies 400")
    void refusals() throws Exception {
        String ticket = resolvedWithPersonalData();

        kb.promote(OTHER_TEAM_TECHNICIAN_ID, ticket, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ERR_UNAUTHORIZED_OPERATION"));
        kb.promote(KnowledgeBaseTestSupport.EMPLOYEE_ID, ticket, null).andExpect(status().isForbidden());
        kb.promote(null, ticket, null).andExpect(status().isUnauthorized());
        kb.promote(KnowledgeBaseTestSupport.ADMIN_ID, "NET-2099-999999", null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ERR_SERVICE_NOT_FOUND"));
        kb.perform(URL, KnowledgeBaseTestSupport.ADMIN_ID, Map.of("title", "no source"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ERR_VALIDATION_FAILED"));
        kb.promote(KnowledgeBaseTestSupport.ADMIN_ID, ticket, "x".repeat(256))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ERR_VALIDATION_FAILED"));
        kb.perform(URL, KnowledgeBaseTestSupport.ADMIN_ID, "{broken")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ERR_MALFORMED_JSON"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_articles WHERE source_incident_id = ?",
                Integer.class, ticket)).isZero();
    }

    @Test
    @DisplayName("Promote A, report B: B gets A's article (not A itself), and a vote on the article is counted")
    void articleIsSuggestedOnceAndVotable() throws Exception {
        String a = resolvedWithPersonalData();
        kb.promote(KnowledgeBaseTestSupport.TECHNICIAN_ID, a, "Renew the AnyConnect VPN gateway certificate")
                .andExpect(status().isCreated());
        String b = kb.ticketWithTechnician("AnyConnect VPN tunnel fails with certificate error",
                "Since this morning the VPN gateway refuses my AnyConnect tunnel and shows a certificate warning.");

        JsonNode list = kb.suggestions(b, KnowledgeBaseTestSupport.TECHNICIAN_ID);
        JsonNode article = KnowledgeBaseTestSupport.find(list, "KB-" + a);

        assertThat(article).as("A's article among B's suggestions: " + list).isNotNull();
        assertThat(article.get("sourceType").asText()).isEqualTo("KB_ARTICLE");
        assertThat(article.get("sourceDisplayId").asText()).isEqualTo("KB-" + a);
        assertThat(article.get("title").asText()).isEqualTo("Renew the AnyConnect VPN gateway certificate");
        assertThat(KnowledgeBaseTestSupport.find(list, a)).as("the promoted ticket itself is not offered again").isNull();

        kb.perform("/api/technician/incidents/" + b + "/suggested-resolutions/votes", KnowledgeBaseTestSupport.TECHNICIAN_ID,
                        Map.of("sourceType", "KB_ARTICLE", "sourceId", "KB-" + a, "worked", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workedVotes").value(1));
        assertThat(KnowledgeBaseTestSupport.find(kb.suggestions(b, KnowledgeBaseTestSupport.TECHNICIAN_ID), "KB-" + a)
                .get("workedVotes").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-043: the technician view says 'promotable' for the resolver and a lead, not for an open ticket or employees")
    void promotableFlag() throws Exception {
        String resolved = resolvedWithPersonalData();
        String open = kb.ticketWithTechnician("AnyConnect VPN drops at the gateway",
                "The VPN gateway drops my AnyConnect tunnel every few minutes from home.");

        assertThat(promotable(resolved, KnowledgeBaseTestSupport.TECHNICIAN_ID)).isTrue();
        assertThat(promotable(resolved, TEAM_LEAD_ID)).isTrue();
        assertThat(promotable(open, KnowledgeBaseTestSupport.TECHNICIAN_ID)).isFalse();
        String queue = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/technician/queue")
                        .header("Authorization", kb.tokenOf(KnowledgeBaseTestSupport.TECHNICIAN_ID)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode card = KnowledgeBaseTestSupport.findById(objectMapper.readTree(queue), resolved);
        assertThat(card).isNotNull();
        assertThat(card.get("promotable").asBoolean()).as("the queue carries the flag too").isTrue();
        String employeeView = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/employee/incidents/" + resolved).header("Authorization", kb.tokenOf(KnowledgeBaseTestSupport.EMPLOYEE_ID)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(employeeView).doesNotContain("promotable");
    }

    private boolean promotable(String ticket, String userId) throws Exception {
        String json = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/technician/incidents/" + ticket).header("Authorization", kb.tokenOf(userId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("promotable").asBoolean();
    }

    @Test
    @DisplayName("A reopened ticket is never offered its own article")
    void ownArticleNotSuggested() throws Exception {
        String a = resolvedWithPersonalData();
        kb.promote(KnowledgeBaseTestSupport.TECHNICIAN_ID, a, null).andExpect(status().isCreated());
        kb.send("/api/employee/incidents/" + a + "/reject", KnowledgeBaseTestSupport.EMPLOYEE_ID,
                Map.of("reason", "The VPN still refuses my tunnel after the fix."));

        JsonNode list = kb.suggestions(a, KnowledgeBaseTestSupport.TECHNICIAN_ID);

        assertThat(KnowledgeBaseTestSupport.find(list, "KB-" + a)).as("own article: " + list).isNull();
    }
}
