package com.iitsm.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract of the technician roster behind the Team Lead tab (Phase M1): a team lead sees the members of their own
 * support team only, an administrator sees every technician, nobody else may read it, and the JSON carries the field
 * names the screen reads (the screen once read {@code activeIncidentsCount} while the API sent
 * {@code activeIncidentCount}, so every technician showed no load).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class TeamLeadRosterScopeTest {

    private static final String ROSTER = "/api/admin/technicians";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private CompanySeeder companySeeder;

    @BeforeEach
    void seed() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    private String tokenOf(String userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + tokenProvider.generateToken(user);
    }

    private JsonNode roster(String userId) throws Exception {
        String body = mockMvc.perform(get(ROSTER).header("Authorization", tokenOf(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.get("id").asText()));
        return ids;
    }

    @Test
    @DisplayName("A team lead gets exactly the members of their own support team, themselves included")
    void leadSeesOnlyOwnTeam() throws Exception {
        String teamId = technicianRepository.findById("LEAD-NET").orElseThrow().getSupportTeamId();
        List<String> expected = technicianRepository.findAll().stream()
                .filter(t -> teamId.equals(t.getSupportTeamId()))
                .map(Technician::getId)
                .toList();

        JsonNode result = roster("LEAD-NET");

        assertThat(expected).hasSizeGreaterThan(1);
        assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(expected);
        result.forEach(n -> assertThat(n.get("supportTeamId").asText()).isEqualTo(teamId));
    }

    @Test
    @DisplayName("Two leads of different teams see different, non-overlapping rosters")
    void leadsOfDifferentTeamsDoNotOverlap() throws Exception {
        List<String> network = ids(roster("LEAD-NET"));
        List<String> database = ids(roster("LEAD-DB"));

        assertThat(network).contains("LEAD-NET", "TECH-NET-1").doesNotContain("LEAD-DB");
        assertThat(database).contains("LEAD-DB").doesNotContain("LEAD-NET", "TECH-NET-1");
        assertThat(network).doesNotContainAnyElementsOf(database);
    }

    @Test
    @DisplayName("An administrator still sees every technician")
    void adminSeesEveryone() throws Exception {
        assertThat(ids(roster("ADMIN-SYS-01")))
                .containsExactlyInAnyOrderElementsOf(technicianRepository.findAll().stream().map(Technician::getId).toList());
    }

    @Test
    @DisplayName("Technicians and employees may not read the roster")
    void otherRolesAreRefused() throws Exception {
        mockMvc.perform(get(ROSTER).header("Authorization", tokenOf("TECH-NET-1"))).andExpect(status().isForbidden());
        mockMvc.perform(get(ROSTER).header("Authorization", tokenOf("EMP-DEMO-01"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("The roster JSON carries the fields the Team Lead screen reads for load, role and skills")
    void jsonCarriesTheFieldsTheScreenReads() throws Exception {
        JsonNode member = roster("LEAD-NET").get(0);

        assertThat(member.has("activeIncidentCount")).isTrue();
        assertThat(member.get("activeIncidentCount").isInt()).isTrue();
        assertThat(member.get("maxCapacity").asInt()).isPositive();
        assertThat(member.get("role").asText()).startsWith("ROLE_");
        assertThat(member.get("skills").isArray()).isTrue();
        assertThat(member.has("activeIncidentsCount")).isFalse();
    }
}
