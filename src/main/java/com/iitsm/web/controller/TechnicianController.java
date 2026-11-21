package com.iitsm.web.controller;

import com.iitsm.application.service.IncidentActors;
import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.TeamLeadReassignmentService;
import com.iitsm.application.service.UserService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.User;
import com.iitsm.web.dto.AddNoteRequest;
import com.iitsm.web.dto.FlagWrongDomainRequest;
import com.iitsm.web.dto.ProvideInfoRequest;
import com.iitsm.web.dto.ReassignRequest;
import com.iitsm.web.dto.ResolveIncidentRequest;
import com.iitsm.web.dto.TechnicianIncidentView;
import com.iitsm.web.mapper.IncidentDtoMapper;
import com.iitsm.web.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing operational queue, dashboard, and investigation actions for IT Technicians.
 */
@RestController
@RequestMapping("/api/technician")
@PreAuthorize("hasAnyRole('TECHNICIAN', 'TEAM_LEAD', 'ADMIN')")
public class TechnicianController {

    private final IncidentService incidentService;
    private final TeamLeadReassignmentService reassignmentService;
    private final UserService userService;
    private final CurrentUser currentUser;
    private final Clock clock;

    public TechnicianController(
            IncidentService incidentService,
            TeamLeadReassignmentService reassignmentService,
            UserService userService,
            CurrentUser currentUser,
            Clock clock) {
        this.incidentService = Objects.requireNonNull(incidentService, "incidentService cannot be null");
        this.reassignmentService = Objects.requireNonNull(reassignmentService, "reassignmentService cannot be null");
        this.userService = Objects.requireNonNull(userService, "userService cannot be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    @GetMapping("/queue")
    public ResponseEntity<List<TechnicianIncidentView>> getQueue(Authentication authentication) {
        String technicianId = currentUser.idOf(authentication);
        List<Incident> queue = incidentService.getTechnicianQueue(technicianId);
        List<TechnicianIncidentView> views = mapToTechnicianViews(queue, technicianId);
        return ResponseEntity.ok(views);
    }

    @GetMapping("/team-queue")
    public ResponseEntity<List<TechnicianIncidentView>> getTeamQueue(Authentication authentication) {
        String actorId = currentUser.idOf(authentication);
        List<Incident> queue = incidentService.getTeamQueue(actorId);
        List<TechnicianIncidentView> views = mapToTechnicianViews(queue, actorId);
        return ResponseEntity.ok(views);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<IncidentService.TechnicianDashboardData> getDashboard(Authentication authentication) {
        String technicianId = currentUser.idOf(authentication);
        IncidentService.TechnicianDashboardData data = incidentService.getTechnicianDashboard(technicianId);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/incidents/{id}")
    public ResponseEntity<TechnicianIncidentView> getIncidentDetails(
            @PathVariable("id") String id,
            Authentication authentication) {
        String actorId = currentUser.idOf(authentication);
        Incident incident = incidentService.getIncident(actorId, id);
        return ResponseEntity.ok(mapToTechnicianView(incident, actorId));
    }

    @PostMapping("/incidents/{id}/acknowledge")
    public ResponseEntity<TechnicianIncidentView> acknowledge(
            @PathVariable("id") String id,
            Authentication authentication) {
        String technicianId = currentUser.idOf(authentication);
        Incident incident = incidentService.acknowledge(id, technicianId);
        return ResponseEntity.ok(mapToTechnicianView(incident, technicianId));
    }

    @PostMapping("/incidents/{id}/start-investigation")
    public ResponseEntity<TechnicianIncidentView> startInvestigation(
            @PathVariable("id") String id,
            Authentication authentication) {
        String technicianId = currentUser.idOf(authentication);
        Incident incident = incidentService.startInvestigation(id, technicianId);
        return ResponseEntity.ok(mapToTechnicianView(incident, technicianId));
    }

    @PostMapping("/incidents/{id}/notes")
    public ResponseEntity<TechnicianIncidentView> addNote(
            @PathVariable("id") String id,
            @Valid @RequestBody AddNoteRequest request,
            Authentication authentication) {
        String authorId = currentUser.idOf(authentication);
        Incident incident = incidentService.addNote(id, authorId, request.noteText(), request.internalOnly());
        return ResponseEntity.ok(mapToTechnicianView(incident, authorId));
    }

    @PostMapping("/incidents/{id}/request-info")
    public ResponseEntity<TechnicianIncidentView> requestInfo(
            @PathVariable("id") String id,
            @Valid @RequestBody ProvideInfoRequest request,
            Authentication authentication) {
        String technicianId = currentUser.idOf(authentication);
        Incident incident = incidentService.requestInfo(id, technicianId, request.responseText());
        return ResponseEntity.ok(mapToTechnicianView(incident, technicianId));
    }

    @PostMapping("/incidents/{id}/resolve")
    public ResponseEntity<TechnicianIncidentView> resolve(
            @PathVariable("id") String id,
            @Valid @RequestBody ResolveIncidentRequest request,
            Authentication authentication) {
        String technicianId = currentUser.idOf(authentication);
        Resolution resolution = new Resolution(
                request.rootCause(),
                request.stepsTaken(),
                (request.preventiveAction() != null && !request.preventiveAction().isBlank())
                        ? request.preventiveAction()
                        : "Standard operational procedure applied",
                request.confirmedCategory().name(),
                technicianId,
                clock.instant());
        Incident incident = incidentService.resolve(id, technicianId, resolution);
        return ResponseEntity.ok(mapToTechnicianView(incident, technicianId));
    }

    /** A team lead gives a ticket of the team to another technician of the same team (ADR-045). */
    @PostMapping("/incidents/{id}/reassign")
    @PreAuthorize("hasRole('TEAM_LEAD')")
    public ResponseEntity<TechnicianIncidentView> reassign(
            @PathVariable("id") String id,
            @Valid @RequestBody ReassignRequest request,
            Authentication authentication) {
        String leadId = currentUser.idOf(authentication);
        Incident incident = reassignmentService.reassign(id, leadId, request.technicianId(), request.reason());
        return ResponseEntity.ok(mapToTechnicianView(incident, leadId));
    }

    @PostMapping("/incidents/{id}/flag-wrong-domain")
    public ResponseEntity<Void> flagWrongDomain(
            @PathVariable("id") String id,
            @Valid @RequestBody FlagWrongDomainRequest request,
            Authentication authentication) {
        String technicianId = currentUser.idOf(authentication);
        incidentService.flagWrongDomain(id, technicianId, request.reason());
        return ResponseEntity.noContent().build();
    }

    /** A list of tickets; the names of the resolvers come from one lookup for the whole list (ADR-037). */
    private List<TechnicianIncidentView> mapToTechnicianViews(List<Incident> incidents, String viewerId) {
        Map<String, String> names = userService.namesById(IncidentActors.ofAll(incidents, i -> List.of()));
        User viewer = userService.findById(viewerId).orElse(null);
        return incidents.stream().map(i -> mapToTechnicianView(i, names, viewer)).collect(Collectors.toList());
    }

    /** {@code viewerId} is the signed-in user; it decides whether the card offers "Promote to knowledge base" (ADR-043). */
    private TechnicianIncidentView mapToTechnicianView(Incident incident, String viewerId) {
        return mapToTechnicianView(incident, userService.namesById(IncidentActors.of(incident, List.of())),
                userService.findById(viewerId).orElse(null));
    }

    private TechnicianIncidentView mapToTechnicianView(Incident incident, Map<String, String> names, User viewer) {
        User reporter = userService.findById(incident.getReporterId()).orElse(null);
        String assigneeName = null;
        if (incident.getAssigneeId() != null) {
            assigneeName = userService.findById(incident.getAssigneeId())
                    .map(User::getName)
                    .orElse(incident.getAssigneeId());
        }
        return IncidentDtoMapper.toTechnicianView(incident, reporter, incident.getAssigneeId(), assigneeName, names, viewer);
    }
}
