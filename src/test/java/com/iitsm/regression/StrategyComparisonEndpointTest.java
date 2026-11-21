package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code POST /api/simulation/strategy-comparison} over HTTP (ADR-044, Phase L3): the shape of the answer, every bad
 * input with its stable code, who may call it, and that the same request gives the same answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class StrategyComparisonEndpointTest {

    private static final String URL = "/api/simulation/strategy-comparison";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
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

    private MockHttpServletRequestBuilder admin(String body) {
        MockHttpServletRequestBuilder request = post(URL).header("Authorization", tokenOf("ADMIN-SYS-01"));
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode ok(String body) throws Exception {
        String json = mockMvc.perform(admin(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode failure(String body, int expectedStatus) throws Exception {
        String json = mockMvc.perform(admin(body)).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private static JsonNode withoutRunTime(JsonNode response) {
        ObjectNode copy = response.deepCopy();
        copy.remove("runMillis");
        return copy;
    }

    @Test
    @DisplayName("an empty body runs the defaults on the seeded roster: five strategies, eight metrics, the assumptions and the notice")
    void defaultsOnTheRoster() throws Exception {
        long started = System.nanoTime();
        JsonNode response = ok("{}");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        JsonNode parameters = response.get("parameters");
        assertThat(parameters.get("seed").asLong()).isEqualTo(42);
        assertThat(parameters.get("ticketCount").asInt()).isEqualTo(500);
        assertThat(parameters.get("replications").asInt()).isEqualTo(10);
        assertThat(parameters.get("arrivalsPerHour").asDouble()).isEqualTo(15.0);
        assertThat(parameters.get("strategies").findValuesAsText("")).isEmpty();
        assertThat(parameters.get("strategies")).hasSize(5);
        assertThat(response.get("poolSource").asText()).isEqualTo("ROSTER");
        assertThat(response.get("technicianCount").asInt()).isEqualTo(24);
        assertThat(response.get("referenceStrategy").asText()).isEqualTo("WEIGHTED_COMPOSITE");
        assertThat(response.get("metrics")).hasSize(8);
        assertThat(response.get("strategies")).hasSize(5);
        assertThat(response.get("assumptions").size()).isGreaterThanOrEqualTo(6);
        assertThat(response.get("notice").asText()).contains("synthetic").contains("not production data");
        assertThat(response.get("runMillis").asLong()).isBetween(0L, 15_000L);
        assertThat(elapsedMillis).as("the whole request at the defaults").isLessThan(15_000L);
        response.get("winners").fields().forEachRemaining(e -> assertThat(e.getValue().size()).isGreaterThanOrEqualTo(1));
    }

    @Test
    @DisplayName("each strategy carries its mean, spread, interval and replications per metric; only the others a difference vs the reference")
    void strategyShape() throws Exception {
        JsonNode response = ok("{\"ticketCount\": 120, \"replications\": 3}");

        for (JsonNode strategy : response.get("strategies")) {
            boolean reference = strategy.get("name").asText().equals("WEIGHTED_COMPOSITE");
            assertThat(strategy.get("reference").asBoolean()).isEqualTo(reference);
            JsonNode metrics = strategy.get("metrics");
            assertThat(metrics.size()).isEqualTo(8);
            for (JsonNode metric : metrics) {
                assertThat(metric.get("perReplication")).hasSize(3);
                assertThat(metric.get("mean").asDouble()).isNotNegative();
                assertThat(metric.get("stdDev").asDouble()).isNotNegative();
                assertThat(metric.get("ci95HalfWidth").asDouble()).isNotNegative();
                assertThat(metric.get("differenceVsReference").isNull()).isEqualTo(reference);
                assertThat(metric.get("distinguishable").isNull()).isEqualTo(reference);
            }
            assertThat(metrics.get("TICKETS_GINI").get("mean").asDouble()).isLessThan(1.0);
            assertThat(metrics.get("SLA_BREACH_RATE").get("mean").asDouble()).isBetween(0.0, 1.0);
        }
    }

    @Test
    @DisplayName("no body at all gives the same answer as an empty object")
    void noBody() throws Exception {
        assertThat(withoutRunTime(ok(null))).isEqualTo(withoutRunTime(ok("{}")));
    }

    @Test
    @DisplayName("explicit parameters are echoed, strategy names are normalised and the reference falls back to the first one")
    void explicitParameters() throws Exception {
        JsonNode response = ok("{\"seed\": 7, \"ticketCount\": 100, \"replications\": 2, \"arrivalsPerHour\": 20.5,"
                + " \"strategies\": [\"skill_based\", \" round_robin \", \"SKILL_BASED\"]}");

        JsonNode parameters = response.get("parameters");
        assertThat(parameters.get("seed").asLong()).isEqualTo(7);
        assertThat(parameters.get("ticketCount").asInt()).isEqualTo(100);
        assertThat(parameters.get("replications").asInt()).isEqualTo(2);
        assertThat(parameters.get("arrivalsPerHour").asDouble()).isEqualTo(20.5);
        List<String> names = new ArrayList<>();
        response.get("strategies").forEach(s -> names.add(s.get("name").asText()));
        assertThat(names).containsExactly("ROUND_ROBIN", "SKILL_BASED");
        assertThat(response.get("referenceStrategy").asText()).isEqualTo("ROUND_ROBIN");
    }

    @Test
    @DisplayName("read-only and idempotent: the same request gives the same answer (except the run time); another seed does not")
    void idempotent() throws Exception {
        String body = "{\"seed\": 99, \"ticketCount\": 150, \"replications\": 3}";

        assertThat(withoutRunTime(ok(body))).isEqualTo(withoutRunTime(ok(body)));
        assertThat(withoutRunTime(ok("{\"seed\": 100, \"ticketCount\": 150, \"replications\": 3}")))
                .isNotEqualTo(withoutRunTime(ok(body)));
    }

    static Stream<Arguments> badInputs() {
        return Stream.of(
                Arguments.of("{\"ticketCount\": 0}", "ticketCount"),
                Arguments.of("{\"ticketCount\": 5001}", "ticketCount"),
                Arguments.of("{\"ticketCount\": -5}", "ticketCount"),
                Arguments.of("{\"replications\": 0}", "replications"),
                Arguments.of("{\"replications\": 51}", "replications"),
                Arguments.of("{\"arrivalsPerHour\": 0}", "arrivalsPerHour"),
                Arguments.of("{\"arrivalsPerHour\": 0.01}", "arrivalsPerHour"),
                Arguments.of("{\"arrivalsPerHour\": 1000.5}", "arrivalsPerHour"),
                Arguments.of("{\"ticketCount\": 5000, \"replications\": 6}", "ticketCount"),
                Arguments.of("{\"ticketCount\": 1000, \"replications\": 50}", "ticketCount"),
                Arguments.of("{\"strategies\": [\"FASTEST\"]}", "strategies"),
                Arguments.of("{\"strategies\": [\"LOAD_BASED\", \"\"]}", "strategies"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badInputs")
    @DisplayName("ADR-027: a bad value is HTTP 400 ERR_VALIDATION_FAILED and the message names the field")
    void badValuesAreRefused(String body, String field) throws Exception {
        JsonNode error = failure(body, 400);

        assertThat(error.get("code").asText()).isEqualTo("ERR_VALIDATION_FAILED");
        assertThat(error.get("message").asText()).contains("'" + field + "'");
    }

    @Test
    @DisplayName("an unknown strategy lists the valid ones")
    void unknownStrategyListsTheValidOnes() throws Exception {
        assertThat(failure("{\"strategies\": [\"FASTEST\"]}", 400).get("message").asText())
                .contains("FASTEST").contains("ROUND_ROBIN").contains("WEIGHTED_COMPOSITE");
    }

    @Test
    @DisplayName("broken JSON, a wrong type and a non-object body are HTTP 400 ERR_MALFORMED_JSON")
    void malformedBodies() throws Exception {
        for (String body : List.of("{not json", "{\"ticketCount\": \"many\"}", "{\"seed\": \"abc\"}",
                "{\"strategies\": \"LOAD_BASED\"}", "[1, 2]", "{\"arrivalsPerHour\": true}")) {
            assertThat(failure(body, 400).get("code").asText()).as(body).isEqualTo("ERR_MALFORMED_JSON");
        }
    }

    @Test
    @DisplayName("only administrators: no token is 401; a technician, a team lead and an employee are 403")
    void onlyAdministrators() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        for (String userId : List.of("TECH-NET-1", "LEAD-NET", "EMP-DEMO-01")) {
            mockMvc.perform(post(URL).header("Authorization", tokenOf(userId)).contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("it is a POST: a GET is HTTP 405 ERR_METHOD_NOT_ALLOWED")
    void getIsNotAllowed() throws Exception {
        String json = mockMvc.perform(get(URL).header("Authorization", tokenOf("ADMIN-SYS-01")))
                .andExpect(status().isMethodNotAllowed()).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(json).get("code").asText()).isEqualTo("ERR_METHOD_NOT_ALLOWED");
    }
}
