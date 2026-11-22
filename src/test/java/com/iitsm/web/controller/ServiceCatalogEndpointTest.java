package com.iitsm.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitsm.domain.model.ItService;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** ADR-049: GET /api/admin/services lists the seeded IT services for the administrator's filter, nobody else's. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceCatalogEndpointTest {

    private static final String URL = "/api/admin/services";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private ItServiceRepository itServiceRepository;
    @Autowired private CompanySeeder companySeeder;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    private String bearer(String userId) {
        return "Bearer " + tokenProvider.generateToken(userRepository.findById(userId).orElseThrow());
    }

    @Test
    @DisplayName("Administrator gets every service, sorted by name, with id, name and criticality only")
    void administratorGetsTheCatalogue() throws Exception {
        String body = mockMvc.perform(get(URL).header("Authorization", bearer("ADMIN-SYS-01")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> options = JsonPath.read(body, "$");
        List<String> expectedIds = itServiceRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(ItService::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ItService::getId))
                .map(ItService::getId).toList();
        assertThat(options).isNotEmpty();
        assertThat(options).extracting(o -> o.get("id")).containsExactlyElementsOf(expectedIds);
        assertThat(options).allSatisfy(o -> assertThat(o.keySet())
                .containsExactlyInAnyOrder("id", "name", "businessCriticality"));
    }

    @Test
    @DisplayName("Employee and technician are refused with 403")
    void otherRolesAreForbidden() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", bearer("EMP-DEMO-01"))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).header("Authorization", bearer("TECH-NET-1"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("No token is 401")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }
}
