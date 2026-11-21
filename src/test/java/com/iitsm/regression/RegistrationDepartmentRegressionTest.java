package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Remaining-defects plan, Phase E (ADR-033, stress-audit item L4, decision D12): the department of a self-registered
 * employee must be one the ticket numbering knows (any profile). A typo used to get a department code and a ticket
 * counter of its own. The registration form offers a fixed list; every entry of it must be accepted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class RegistrationDepartmentRegressionTest {

    private static final String PASSWORD = "Password123!";
    private static final Path INDEX_HTML = Path.of("src/main/resources/static/index.html");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private ResultActions register(String department) throws Exception {
        String email = "dept." + UUID.randomUUID().toString().substring(0, 8) + "@nimbusfinserve.com";
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "Dept Test", "email", email,
                        "password", PASSWORD, "department", department))));
    }

    @Test
    @DisplayName("L4: an unregistered department is a 400 that names the field and the valid choices")
    void unknownDepartmentIsRefused() throws Exception {
        register("Underwater Basket Weaving")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("department")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Engineering")));
    }

    @Test
    @DisplayName("L4: a typo of a real department is refused too")
    void typoIsRefused() throws Exception {
        register("Enginering").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("L4: a missing department still defaults to Engineering (the form always sends one)")
    void missingDepartmentDefaults() throws Exception {
        String email = "dept." + UUID.randomUUID().toString().substring(0, 8) + "@nimbusfinserve.com";
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Dept Test", "email", email,
                                "password", PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("L4: every department the registration form offers is accepted")
    void everyFormOptionIsAccepted() throws Exception {
        String html = Files.readString(INDEX_HTML, StandardCharsets.UTF_8);
        int start = html.indexOf("id=\"reg-department\"");
        int end = html.indexOf("</select>", start);
        Matcher option = Pattern.compile("<option value=\"([^\"]+)\"").matcher(html.substring(start, end));
        List<String> offered = new ArrayList<>();
        while (option.find()) {
            offered.add(option.group(1).replace("&amp;", "&"));
        }
        assertThat(offered).as("the form offers departments").hasSizeGreaterThanOrEqualTo(5);

        for (String department : offered) {
            register(department).andExpect(status().isOk());
        }
    }
}
