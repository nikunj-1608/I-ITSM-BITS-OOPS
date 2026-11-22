package com.iitsm.regression;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Remaining-defects plan, Phase E (ADR-033, stress-audit item L4, decision D12), with the settings the prod and demo
 * profiles use: registration limited to the company's e-mail domain and the API documentation switched off. The test
 * profile itself allows any domain (see {@code ExposureConfigContractTest} for the profile files).
 */
@SpringBootTest(properties = {
        "iitsm.security.allowed-email-domains=nimbusfinserve.com",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class ExposureHardeningRegressionTest {

    private static final String PASSWORD = "Password123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserDetailsService userDetailsService;
    @Autowired private CompanySeeder companySeeder;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    private ResultActions register(String email, String department) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "Outsider", "email", email,
                        "password", PASSWORD, "department", department))));
    }

    private String unique(String domain) {
        return "reg." + UUID.randomUUID().toString().substring(0, 8) + "@" + domain;
    }

    @Test
    @DisplayName("L4: registration with an external e-mail domain is refused with a 400 that names the field")
    void externalDomainIsRefused() throws Exception {
        register(unique("gmail.com"), "Engineering")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("email")));
    }

    @Test
    @DisplayName("L4: registration with the company domain still works")
    void companyDomainIsAccepted() throws Exception {
        register(unique("nimbusfinserve.com"), "Engineering").andExpect(status().isOk());
    }

    @Test
    @DisplayName("L4: a refused registration creates no account")
    void refusedRegistrationCreatesNothing() throws Exception {
        String email = unique("gmail.com");

        register(email, "Engineering").andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("L4: the API documentation is not served when it is switched off")
    void swaggerIsClosed() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("L4: the JWT filter's user lookup knows no alias either (a token can only name a real account)")
    void detailsServiceKnowsNoAlias() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("tech.database@nimbusfinserve.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("L4: the retired login alias tech.database@ no longer signs in as tech.db1@")
    void loginAliasIsGone() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "tech.database@nimbusfinserve.com",
                                "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "tech.db1@nimbusfinserve.com",
                                "password", PASSWORD))))
                .andExpect(status().isOk());
    }
}
