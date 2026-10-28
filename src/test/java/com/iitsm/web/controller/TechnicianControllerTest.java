package com.iitsm.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import com.iitsm.web.dto.AddNoteRequest;
import com.iitsm.web.dto.FlagWrongDomainRequest;
import com.iitsm.web.dto.ProvideInfoRequest;
import com.iitsm.web.dto.ResolveIncidentRequest;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class TechnicianControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.iitsm.infrastructure.persistence.CompanySeeder companySeeder;

    private User technician;
    private String token;
    private Incident incident;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }

        // Tech from CompanySeeder: "TECH-NET-1"
        technician = userRepository.findById("TECH-NET-1").orElseThrow();
        token = tokenProvider.generateToken(technician);

        incident = incidentService.createIncident(
                "EMP-0001",
                "Internal web portal 500 error",
                "Receiving HTTP 500 when accessing employee benefits portal.");

        // Assign explicitly to this technician so actions are authorized
        incidentService.assign(incident.getId(), "ADMIN-SYS-01", technician.getId());
    }

    @Test
    @DisplayName("Technician can inspect assigned queue and dashboard")
    void testQueueAndDashboard() throws Exception {
        mockMvc.perform(get("/api/technician/queue")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/technician/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.technicianId").value(technician.getId()))
                .andExpect(jsonPath("$.maxCapacity").isNumber());
    }

    @Test
    @DisplayName("Technician can perform lifecycle workflow steps")
    void testLifecycleActions() throws Exception {
        // 1. Get Details
        mockMvc.perform(get("/api/technician/incidents/" + incident.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incident.getId()));

        // 2. Acknowledge
        mockMvc.perform(post("/api/technician/incidents/" + incident.getId() + "/acknowledge")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

        // 3. Start Investigation
        mockMvc.perform(post("/api/technician/incidents/" + incident.getId() + "/start-investigation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // 4. Add Note
        AddNoteRequest noteReq = new AddNoteRequest("Checked Nginx logs, looking at upstream", true);
        mockMvc.perform(post("/api/technician/incidents/" + incident.getId() + "/notes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noteReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").isArray());

        // 5. Request Info
        ProvideInfoRequest infoReq = new ProvideInfoRequest("Which browser version are you using?");
        mockMvc.perform(post("/api/technician/incidents/" + incident.getId() + "/request-info")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(infoReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_INFO"));

        // Provide info from reporter
        incidentService.provideInfo(incident.getId(), "EMP-0001", "Chrome 125");

        // 6. Resolve
        ResolveIncidentRequest resolveReq = new ResolveIncidentRequest(
                "Upstream container out of memory",
                "Restarted container with increased heap limit",
                "Added monitoring rule for memory usage",
                IncidentCategory.APPLICATION);

        mockMvc.perform(post("/api/technician/incidents/" + incident.getId() + "/resolve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution.rootCause").value("Upstream container out of memory"));
    }

    @Test
    @Transactional
    @DisplayName("Technician can flag wrong domain")
    void testFlagWrongDomain() throws Exception {
        FlagWrongDomainRequest req = new FlagWrongDomainRequest("Ticket belongs to Cloud infrastructure team");
        mockMvc.perform(post("/api/technician/incidents/" + incident.getId() + "/flag-wrong-domain")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    // -----------------------------------------------------------------------
    // Regression: solved tickets must remain visible in the technician queue
    // -----------------------------------------------------------------------

    /**
     * Verifies that after a technician resolves an incident, the ticket still
     * appears in the /api/technician/queue response with status RESOLVED.
     * <p>
     * Previously a {@code .filter(i -> i.getStatus() != CLOSED)} guard in
     * {@link com.iitsm.application.service.IncidentLifecycleService#getTechnicianQueue}
     * stripped RESOLVED tickets as well when {@code showSolvedTickets=false}.
     * The feature flag {@code iitsm.show-solved-tickets=true} (now the default)
     * ensures both RESOLVED and CLOSED tickets are returned so they are rendered
     * with the appropriate badge instead of silently disappearing.
     */
    @Test
    @Transactional
    @DisplayName("shouldReturnSolvedTicketsForTechnician – RESOLVED ticket appears in queue")
    void shouldReturnSolvedTicketsForTechnician() throws Exception {
        // ── Arrange: drive the incident to RESOLVED ───────────────────────
        incidentService.acknowledge(incident.getId(), technician.getId());
        incidentService.startInvestigation(incident.getId(), technician.getId());

        Resolution resolution = new Resolution(
                "Root cause identified: misconfigured upstream",
                "Rolled back configuration and restarted service",
                "Added alert for configuration drift",
                IncidentCategory.APPLICATION.name(),
                technician.getId(),
                Instant.now());
        incidentService.resolve(incident.getId(), technician.getId(), resolution);

        // ── Act: fetch the technician queue ───────────────────────────────
        String responseBody = mockMvc.perform(get("/api/technician/queue")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // ── Assert: the RESOLVED ticket is present ────────────────────────
        mockMvc.perform(get("/api/technician/queue")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                // At least one element in the array should have status RESOLVED
                .andExpect(jsonPath(
                        "$[?(@.id == '" + incident.getId() + "')].status",
                        Matchers.hasItem("RESOLVED")));
    }

    @Test
    @Transactional
    @DisplayName("Technician cannot resolve incident while in ACKNOWLEDGED state until investigation started")
    void testResolveRejectedInAcknowledgedStateUntilInvestigationStarted() throws Exception {
        // 1. Move to ACKNOWLEDGED
        mockMvc.perform(post("/api/technician/incidents/" + incident.getId() + "/acknowledge")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.allowedActions", Matchers.hasItem("startInvestigation")))
                .andExpect(jsonPath("$.allowedActions", Matchers.not(Matchers.hasItem("resolve"))));

        // 2. Premature resolve attempt must be rejected by State Pattern with 409 CONFLICT
        ResolveIncidentRequest prematureResolveReq = new ResolveIncidentRequest(
                "Premature fix attempt",
                "Applied fix directly without investigating",
                "None",
                IncidentCategory.APPLICATION);

        mockMvc.perform(post("/api/technician/incidents/" + incident.getId() + "/resolve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prematureResolveReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ERR_INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.message", Matchers.containsString("Action 'resolve' is not allowed when incident is in state 'ACKNOWLEDGED'")));

        // 3. Start investigation -> moves to IN_PROGRESS
        mockMvc.perform(post("/api/technician/incidents/" + incident.getId() + "/start-investigation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.allowedActions", Matchers.hasItem("resolve")));

        // 4. Now resolve succeeds
        ResolveIncidentRequest validResolveReq = new ResolveIncidentRequest(
                "Root cause identified after investigation",
                "Applied fix and verified logs",
                "Monitoring rule added",
                IncidentCategory.APPLICATION);

        mockMvc.perform(post("/api/technician/incidents/" + incident.getId() + "/resolve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validResolveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution.rootCause").value("Root cause identified after investigation"));
    }
}
