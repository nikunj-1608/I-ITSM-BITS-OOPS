package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.domain.model.FactorContribution;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.classification.IncidentClassifier;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Remaining-defects plan, Phase C1 (ADR-027): bad client input must end as a 4xx with a stable error code, never as
 * HTTP 500 (stress-audit items M5, M6, M7, M8). One scenario per example the audit observed, with the request that
 * produced the 500, plus positive controls (valid requests still work) and a guard that a genuine server bug is
 * still a logged 500: the fix converts errors at their source, it does not turn every exception into a 4xx.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
@Import(InputValidationRegressionTest.UnexpectedFailureProbe.class)
class InputValidationRegressionTest {

    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String REPORTER_ID = "EMP-DEMO-01";
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final String INVALID_PARAMETER = "ERR_INVALID_PARAMETER";
    private static final String VALIDATION_FAILED = "ERR_VALIDATION_FAILED";

    /** Endpoints that fail with the kind of exception a real bug would throw, to prove those stay HTTP 500. */
    @RestController
    static class UnexpectedFailureProbe {

        @GetMapping("/api/test-probe/illegal-state")
        String illegalState() {
            throw new IllegalStateException("simulated server bug");
        }

        @GetMapping("/api/test-probe/illegal-argument")
        String illegalArgument() {
            throw new IllegalArgumentException("simulated programming error");
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private IncidentClassifier classifier;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    private String adminToken() {
        User admin = userRepository.findById(ADMIN_ID).orElseThrow();
        return "Bearer " + tokenProvider.generateToken(admin);
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", adminToken()).accept(MediaType.APPLICATION_JSON));
    }

    private ResultActions asAdminJson(MockHttpServletRequestBuilder request, String body) throws Exception {
        return asAdmin(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** A ticket waiting in the triage queue, exactly as the intake leaves a low-confidence ticket. */
    private Incident triageTicket() {
        Incident incident = new Incident("INC-VAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "VPN gateway drops " + UUID.randomUUID().toString().substring(0, 8),
                "The vpn gateway keeps dropping connections for the whole office " + UUID.randomUUID(), REPORTER_ID, NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.40,
                List.of(new FactorContribution("Crit", 0.4, 1.0, 0.4, "test")), List.of(), NOW, null, null);
        incident.classify(IncidentCategory.APPLICATION, "SVC-VPN", decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        incident.setNeedsTriage(true);
        return incidentRepository.save(incident);
    }

    private Instant virtualTime() throws Exception {
        String body = asAdmin(get("/api/simulation/status")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Instant.parse(objectMapper.readTree(body).get("virtualTime").asText());
    }

    // ---- M5: enum and date request parameters -------------------------------------------------------------------

    @Test
    @DisplayName("M5: an unknown priority, category, status or an unparseable date in the incident search is a 400 naming the parameter")
    void invalidSearchParametersAreBadRequests() throws Exception {
        for (String query : List.of("priority=P9", "category=PRINTER", "status=LIMBO", "fromDate=yesterday")) {
            String parameter = query.substring(0, query.indexOf('='));
            asAdmin(get("/api/admin/incidents?" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(INVALID_PARAMETER))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(parameter));
        }
    }

    @Test
    @DisplayName("M5 control: valid search parameters still work")
    void validSearchStillWorks() throws Exception {
        asAdmin(get("/api/admin/incidents?priority=P1&priority=P3&category=NETWORK&status=CLASSIFIED&fromDate=2026-01-01T00:00:00Z"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("M5: an unknown designation is a 400 that lists the valid ones")
    void unknownDesignationIsABadRequest() throws Exception {
        asAdminJson(put("/api/admin/users/" + REPORTER_ID + "/designation"), "{\"designation\":\"OVERLORD\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value(containsString("OVERLORD")));
    }

    @Test
    @DisplayName("M5: an unknown assignment strategy is a 400 and the active strategy is unchanged")
    void unknownStrategyIsABadRequest() throws Exception {
        String activeBefore = activeStrategy();

        asAdminJson(put("/api/admin/assignment/strategies/active"), "{\"strategyName\":\"NOPE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value(containsString("NOPE")));

        assertThat(activeStrategy()).isEqualTo(activeBefore);
        asAdminJson(put("/api/admin/assignment/strategies/active"), "{\"strategyName\":\"" + activeBefore + "\"}")
                .andExpect(status().isOk());
    }

    private String activeStrategy() throws Exception {
        JsonNode strategies = objectMapper.readTree(asAdmin(get("/api/admin/assignment/strategies"))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode strategy : strategies) {
            if (strategy.get("active").asBoolean()) {
                return strategy.get("name").asText();
            }
        }
        throw new AssertionError("no active strategy");
    }

    @Test
    @DisplayName("M5: an unknown simulation scenario is a 400")
    void unknownScenarioIsABadRequest() throws Exception {
        asAdmin(post("/api/simulation/run/S99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value(containsString("S99")));
    }

    @Test
    @DisplayName("M5: a speed that is not positive or not a number is a 400 and the speed is unchanged")
    void invalidSpeedIsABadRequest() throws Exception {
        for (String multiplier : List.of("-5", "0")) {
            asAdmin(post("/api/simulation/speed?multiplier=" + multiplier))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(VALIDATION_FAILED));
        }
        asAdmin(post("/api/simulation/speed?multiplier=fast"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(INVALID_PARAMETER));
        asAdmin(get("/api/simulation/status")).andExpect(jsonPath("$.speedMultiplier").value(1));
    }

    @Test
    @DisplayName("M5: a wrong HTTP method is a 405 that says which methods are allowed, not a 500")
    void wrongMethodIsMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.code").value("ERR_METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("M5: a missing static resource such as /favicon.ico is a 404, not a 500")
    void missingResourceIsNotFound() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERR_NOT_FOUND"));
    }

    // ---- M8: the simulation clock must not move backwards through the advance endpoint -------------------------

    @Test
    @DisplayName("M8: advancing by zero or a negative number of minutes is a 400 and the clock does not move back")
    void clockCannotBeAdvancedBackwards() throws Exception {
        for (String minutes : List.of("-30", "0")) {
            Instant before = virtualTime();
            asAdmin(post("/api/simulation/advance?minutes=" + minutes))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(VALIDATION_FAILED));
            assertThat(virtualTime()).as("minutes=" + minutes).isAfterOrEqualTo(before);
        }
        asAdmin(post("/api/simulation/advance?minutes=9223372036854775807"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_FAILED));
    }

    @Test
    @DisplayName("M8 control: a valid advance and a valid speed still work")
    void validClockChangesStillWork() throws Exception {
        Instant before = virtualTime();
        asAdmin(post("/api/simulation/advance?minutes=5")).andExpect(status().isOk());
        assertThat(virtualTime()).isAfter(before.plusSeconds(4 * 60));
        asAdmin(post("/api/simulation/speed?multiplier=1")).andExpect(status().isOk());
    }

    // ---- M6 and M7: triage suggestion and reclassify -----------------------------------------------------------

    @Test
    @DisplayName("M5: accept-suggestion with an unknown category is a 400 and the ticket stays in triage")
    void unknownCategoryInAcceptSuggestion() throws Exception {
        Incident ticket = triageTicket();

        asAdminJson(post("/api/admin/triage/" + ticket.getId() + "/accept-suggestion"), "{\"category\":\"PRINTERS\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value(containsString("PRINTERS")));

        assertThat(incidentRepository.findById(ticket.getId()).orElseThrow().isNeedsTriage()).isTrue();
    }

    @Test
    @DisplayName("M7: accept-suggestion without a category (no body, or an empty one) uses the classifier's suggestion, not SOFTWARE")
    void missingCategoryUsesTheClassifiersSuggestion() throws Exception {
        for (String body : new String[] {null, "{}"}) {
            Incident ticket = triageTicket();
            IncidentCategory suggested = classifier.classify(ticket.getTitle(), ticket.getDescription()).category();
            Assumptions.assumeTrue(suggested != IncidentCategory.SOFTWARE,
                    "the test text must not be classified as SOFTWARE, or the test proves nothing");
            MockHttpServletRequestBuilder request = post("/api/admin/triage/" + ticket.getId() + "/accept-suggestion");

            (body == null ? asAdmin(request) : asAdminJson(request, body)).andExpect(status().isOk());

            Incident stored = incidentRepository.findById(ticket.getId()).orElseThrow();
            assertThat(stored.getCategory()).as("body=" + body).isEqualTo(suggested);
            assertThat(stored.isNeedsTriage()).isFalse();
        }
    }

    @Test
    @DisplayName("M6: reclassify and accept-suggestion with an unknown service id are 404 and change nothing")
    void unknownServiceIdIsNotFound() throws Exception {
        Incident ticket = triageTicket();

        asAdminJson(post("/api/admin/incidents/" + ticket.getId() + "/reclassify"),
                "{\"category\":\"SECURITY\",\"serviceId\":\"SVC-DOES-NOT-EXIST\",\"reason\":\"wrong service\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERR_SERVICE_NOT_FOUND"));
        asAdminJson(post("/api/admin/triage/" + ticket.getId() + "/accept-suggestion"),
                "{\"category\":\"NETWORK\",\"serviceId\":\"SVC-DOES-NOT-EXIST\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERR_SERVICE_NOT_FOUND"));

        Incident stored = incidentRepository.findById(ticket.getId()).orElseThrow();
        assertThat(stored.getCategory()).isEqualTo(IncidentCategory.APPLICATION);
        assertThat(stored.getServiceId()).isEqualTo("SVC-VPN");
        assertThat(stored.isNeedsTriage()).isTrue();
    }

    // ---- Guard: genuine bugs must not be hidden as client errors ----------------------------------------------

    @Test
    @DisplayName("Guard: an unexpected exception, including an IllegalArgumentException, is still a 500 and is logged as an error")
    void genuineServerBugsStayServerErrors() throws Exception {
        Logger handlerLog = (Logger) LoggerFactory.getLogger("com.iitsm.web.error.GlobalExceptionHandler");
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        handlerLog.addAppender(lines);
        try {
            for (String path : List.of("/api/test-probe/illegal-state", "/api/test-probe/illegal-argument")) {
                asAdmin(get(path))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("ERR_INTERNAL_SERVER_ERROR"));
            }
            assertThat(lines.list).filteredOn(line -> line.getLevel() == Level.ERROR)
                    .hasSize(2).allSatisfy(line -> assertThat(line.getThrowableProxy()).isNotNull());
        } finally {
            handlerLog.detachAppender(lines);
        }
    }
}
