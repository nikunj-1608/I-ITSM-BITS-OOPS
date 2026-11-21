package com.iitsm.web.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitsm.domain.model.EnumText;
import com.iitsm.domain.model.Priority;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** ADR-027: the standard web errors a client can cause end as 4xx with a stable code, never as the 500 catch-all. */
class GlobalExceptionHandlerInputErrorTest {

    @RestController
    static class StubController {

        @GetMapping("/by-enum")
        String byEnum(@RequestParam("priority") Priority priority) {
            return priority.name();
        }

        @GetMapping("/by-enum-set")
        String byEnumSet(@RequestParam("priority") Set<Priority> priorities) {
            return String.valueOf(priorities.size());
        }

        @GetMapping("/by-number")
        String byNumber(@RequestParam("amount") long amount) {
            return String.valueOf(amount);
        }

        @GetMapping("/required")
        String required(@RequestParam("name") String name) {
            return name;
        }

        @PostMapping("/post-only")
        String postOnly() {
            return "ok";
        }

        @PostMapping(value = "/json-only", consumes = MediaType.APPLICATION_JSON_VALUE)
        String jsonOnly(@RequestBody String body) {
            return body;
        }

        @GetMapping("/gone")
        String gone() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/favicon.ico");
        }

        @GetMapping("/bug")
        String bug() {
            throw new IllegalArgumentException("a programming error, not the clients mistake");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("An unknown enum value is a 400 that names the parameter and lists the valid values")
    void unknownEnumValue() throws Exception {
        mockMvc.perform(get("/by-enum").param("priority", "P9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("ERR_INVALID_PARAMETER"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("priority"))
                .andExpect(jsonPath("$.message").value(containsString("'P9'")))
                .andExpect(jsonPath("$.message").value(containsString(EnumText.validValues(Priority.class))));
    }

    @Test
    @DisplayName("The valid values are listed also when the parameter is a Set of the enum")
    void unknownEnumValueInASet() throws Exception {
        mockMvc.perform(get("/by-enum-set").param("priority", "P1", "P9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value(containsString(EnumText.validValues(Priority.class))));
    }

    @Test
    @DisplayName("Text where a number is expected is a 400 without a list of valid values")
    void notANumber() throws Exception {
        mockMvc.perform(get("/by-number").param("amount", "lots"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_INVALID_PARAMETER"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"))
                .andExpect(jsonPath("$.message").value(containsString("'lots'")));
    }

    @Test
    @DisplayName("A very long invalid value is cut in the message")
    void longValueIsCut() throws Exception {
        mockMvc.perform(get("/by-number").param("amount", "9".repeat(5000) + "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("9".repeat(EnumText.MAX_ECHOED_LENGTH) + "...")))
                .andExpect(content().string(not(containsString("9".repeat(EnumText.MAX_ECHOED_LENGTH + 1)))));
    }

    @Test
    @DisplayName("A missing required parameter is a 400 that names it")
    void missingParameter() throws Exception {
        mockMvc.perform(get("/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_MISSING_PARAMETER"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    @DisplayName("The wrong HTTP method is a 405 with an Allow header and the allowed methods in the message")
    void wrongMethod() throws Exception {
        mockMvc.perform(get("/post-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("ERR_METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value(containsString("GET")))
                .andExpect(jsonPath("$.message").value(containsString("POST")));
    }

    @Test
    @DisplayName("A body with an unsupported content type is a 415")
    void unsupportedMediaType() throws Exception {
        mockMvc.perform(post("/json-only").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("ERR_UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("A missing resource is a 404 that does not echo the path")
    void missingResource() throws Exception {
        mockMvc.perform(get("/gone"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERR_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    @DisplayName("Guard: an IllegalArgumentException from the application is still the 500 catch-all, not a 400")
    void illegalArgumentIsStillAServerError() throws Exception {
        mockMvc.perform(get("/bug"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ERR_INTERNAL_SERVER_ERROR"));
    }
}
