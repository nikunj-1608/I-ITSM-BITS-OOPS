package com.iitsm.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import com.iitsm.web.dto.AssignRequest;
import com.iitsm.web.dto.FeedbackRequest;
import com.iitsm.web.dto.OverridePriorityRequest;
import java.time.Instant;
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
class AdminControllerTest {

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
    private IncidentRepository incidentRepository;

    @Autowired
    private com.iitsm.infrastructure.persistence.CompanySeeder companySeeder;

    private User admin;
    private String token;
    private Incident incident;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }

        admin = userRepository.findById("ADMIN-SYS-01").orElseThrow();
        token = tokenProvider.generateToken(admin);

        incident = incidentService.createIncident(
                "EMP-0001",
                "Exchange server high CPU alert",
                "High load on mail routing component affecting delivery latency.");
    }

    @Test
    @DisplayName("Admin can view system statistics, directory, triage queue and technician roster")
    void testAdminInspectionEndpoints() throws Exception {
        // 1. Stats
        mockMvc.perform(get("/api/admin/stats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncidents").isNumber());

        // 2. Incident Search
        mockMvc.perform(get("/api/admin/incidents?query=Exchange")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 3. Triage Queue
        mockMvc.perform(get("/api/admin/triage-queue")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 4. Technician Roster
        mockMvc.perform(get("/api/admin/technicians")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Admin search accepts repeated priority and category params and a single value")
    void testAdminSearchMultiValueFilters() throws Exception {
        Priority actual = incident.getPriorityDecision().priority();
        Priority other = actual == Priority.P1 ? Priority.P4 : Priority.P1;
        String idMatch = "$[?(@.id=='" + incident.getId() + "')]";

        // Repeated params: ticket's own priority plus another one still matches
        mockMvc.perform(get("/api/admin/incidents?priority=" + other + "&priority=" + actual)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(idMatch).isNotEmpty());

        // Single value (legacy form) that excludes the ticket
        mockMvc.perform(get("/api/admin/incidents?priority=" + other)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(idMatch).isEmpty());

        // Category list: the ticket's category and an unrelated one
        IncidentCategory category = incident.getCategory();
        IncidentCategory unrelated = category == IncidentCategory.STORAGE ? IncidentCategory.CLOUD : IncidentCategory.STORAGE;
        mockMvc.perform(get("/api/admin/incidents?category=" + unrelated + "&category=" + category)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(idMatch).isNotEmpty());
        mockMvc.perform(get("/api/admin/incidents?category=" + unrelated)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(idMatch).isEmpty());

        // Blank value means no filter
        mockMvc.perform(get("/api/admin/incidents?priority=&category=")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(idMatch).isNotEmpty());
    }

    @Test
    @DisplayName("Admin search accepts repeated status params and a single status (ADR-049)")
    void testAdminSearchRepeatedStatusParams() throws Exception {
        IncidentStatus actual = incidentRepository.findById(incident.getId()).orElseThrow().getStatus();
        IncidentStatus other = actual == IncidentStatus.CLOSED ? IncidentStatus.VERIFIED : IncidentStatus.CLOSED;
        String idMatch = "$[?(@.id=='" + incident.getId() + "')]";

        mockMvc.perform(get("/api/admin/incidents?status=" + other + "&status=" + actual)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(idMatch).isNotEmpty());
        mockMvc.perform(get("/api/admin/incidents?status=" + other)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(idMatch).isEmpty());
    }

    @Test
    @DisplayName("Admin search filters by the stored SLA status, one or several values (ADR-049)")
    void testAdminSearchSlaStatusParam() throws Exception {
        SlaStatus actual = incidentRepository.findById(incident.getId()).orElseThrow().getSlaTracker().getStatus();
        SlaStatus other = actual == SlaStatus.MET ? SlaStatus.BREACHED : SlaStatus.MET;
        String idMatch = "$[?(@.id=='" + incident.getId() + "')]";

        mockMvc.perform(get("/api/admin/incidents?slaStatus=" + actual)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(idMatch).isNotEmpty());
        mockMvc.perform(get("/api/admin/incidents?slaStatus=" + other + "&slaStatus=" + actual)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(idMatch).isNotEmpty());
        mockMvc.perform(get("/api/admin/incidents?slaStatus=" + other)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(idMatch).isEmpty());
    }

    @Test
    @DisplayName("An unknown slaStatus or status value is 400 ERR_INVALID_PARAMETER, not 500")
    void testAdminSearchUnknownSlaStatusIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/admin/incidents?slaStatus=LATE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_INVALID_PARAMETER"));
        mockMvc.perform(get("/api/admin/incidents?status=NEW&status=OPEN")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("Admin can perform administrative controls: assign, override priority, close")
    void testAdminActionEndpoints() throws Exception {
        // 1. Get Details
        mockMvc.perform(get("/api/admin/incidents/" + incident.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incident.getId()))
                .andExpect(jsonPath("$.priorityDecision").exists())
                .andExpect(jsonPath("$.slaTracker").exists());

        // 2. Assign
        AssignRequest assignReq = new AssignRequest("TECH-NET-1");
        mockMvc.perform(post("/api/admin/incidents/" + incident.getId() + "/assign")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value("TECH-NET-1"));

        // 3. Override Priority
        OverridePriorityRequest overrideReq = new OverridePriorityRequest(Priority.P1, "CEO escalates high priority outage");
        mockMvc.perform(post("/api/admin/incidents/" + incident.getId() + "/override-priority")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overrideReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.priorityDecision.priority").value("P1"));

        // 4. Advance through lifecycle so incident is in VERIFIED state before closing
        incidentService.acknowledge(incident.getId(), "TECH-NET-1");
        incidentService.startInvestigation(incident.getId(), "TECH-NET-1");
        incidentService.resolve(incident.getId(), "TECH-NET-1",
                new Resolution("Root cause identified and resolved", "Applied configuration fix", "Updated runbook", "INFRASTRUCTURE", "TECH-NET-1", Instant.now()));
        incidentService.verify(incident.getId(), "EMP-0001");

        // 5. Close
        FeedbackRequest closeReq = new FeedbackRequest(5, "Resolved by admin");
        mockMvc.perform(post("/api/admin/incidents/" + incident.getId() + "/close")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(closeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    @DisplayName("Incident detail keeps the existing top-level fields and adds reporter, service, team and audit details")
    void testIncidentDetailIncludesRelatedInformation() throws Exception {
        mockMvc.perform(get("/api/admin/incidents/" + incident.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                // backward-compatible top-level fields (unwrapped AdminIncidentView)
                .andExpect(jsonPath("$.id").value(incident.getId()))
                .andExpect(jsonPath("$.title").value(incident.getTitle()))
                .andExpect(jsonPath("$.priorityDecision.factorContributions").isArray())
                .andExpect(jsonPath("$.slaTracker.resolutionDueAt").exists())
                .andExpect(jsonPath("$.auditTrail").isArray())
                // detail-only fields
                .andExpect(jsonPath("$.reporter.email").isString())
                .andExpect(jsonPath("$.reporter.name").isString())
                .andExpect(jsonPath("$.service.name").isString())
                .andExpect(jsonPath("$.service.criticalityLabel").isString())
                .andExpect(jsonPath("$.supportTeam.name").isString())
                .andExpect(jsonPath("$.publicStatus").isString())
                .andExpect(jsonPath("$.needsTriage").isBoolean())
                .andExpect(jsonPath("$.lastModifiedAt").exists());
    }
}
