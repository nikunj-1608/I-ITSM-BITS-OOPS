package com.iitsm.web.controller;

import com.iitsm.application.dto.TriageQueueItemDto;
import com.iitsm.application.service.AdminIncidentDetailService;
import com.iitsm.application.service.AuditService;
import com.iitsm.application.service.CapacityReconciliationService;
import com.iitsm.application.service.IncidentActors;
import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.SearchService;
import com.iitsm.application.service.ServiceCatalogService;
import com.iitsm.application.service.UserService;
import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.Designation;
import com.iitsm.domain.model.Feedback;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.User;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.web.dto.AdminIncidentDetailView;
import com.iitsm.web.dto.AdminIncidentView;
import com.iitsm.web.dto.AssignRequest;
import com.iitsm.web.dto.CapacityReportView;
import com.iitsm.web.dto.CapacityRepairView;
import com.iitsm.web.dto.FeedbackRequest;
import com.iitsm.web.dto.ItServiceOptionDto;
import com.iitsm.web.dto.OverridePriorityRequest;
import com.iitsm.web.dto.ReassignmentRequestDto;
import com.iitsm.web.dto.ReclassifyRequest;
import com.iitsm.web.dto.RejectReassignmentRequest;
import com.iitsm.web.dto.StrategyInfoDto;
import com.iitsm.web.dto.SwitchStrategyRequest;
import com.iitsm.web.dto.TechnicianSummaryDto;
import com.iitsm.web.mapper.IncidentDtoMapper;
import com.iitsm.application.service.MfaService;
import com.iitsm.web.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing command centre endpoints, directory search, and administrative controls.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final IncidentService incidentService;
    private final SearchService searchService;
    private final UserService userService;
    private final AuditService auditService;
    private final AdminIncidentDetailService incidentDetailService;
    private final CapacityReconciliationService capacityService;
    private final CurrentUser currentUser;
    private final MfaService mfaService;
    private final ServiceCatalogService serviceCatalogService;
    private final Clock clock;

    public AdminController(
            IncidentService incidentService,
            SearchService searchService,
            UserService userService,
            AuditService auditService,
            AdminIncidentDetailService incidentDetailService,
            CapacityReconciliationService capacityService,
            CurrentUser currentUser,
            MfaService mfaService,
            ServiceCatalogService serviceCatalogService,
            Clock clock) {
        this.incidentService = Objects.requireNonNull(incidentService, "incidentService cannot be null");
        this.searchService = Objects.requireNonNull(searchService, "searchService cannot be null");
        this.userService = Objects.requireNonNull(userService, "userService cannot be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService cannot be null");
        this.incidentDetailService = Objects.requireNonNull(incidentDetailService, "incidentDetailService cannot be null");
        this.capacityService = Objects.requireNonNull(capacityService, "capacityService cannot be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser cannot be null");
        this.mfaService = Objects.requireNonNull(mfaService, "mfaService cannot be null");
        this.serviceCatalogService = Objects.requireNonNull(serviceCatalogService, "serviceCatalogService cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    @GetMapping("/stats")
    public ResponseEntity<IncidentService.AdminStats> getStats() {
        return ResponseEntity.ok(incidentService.getAdminStats());
    }

    @GetMapping("/incidents")
    public ResponseEntity<List<AdminIncidentView>> searchIncidents(
            @RequestParam(name = "status", required = false) Set<IncidentStatus> statuses,
            @RequestParam(name = "priority", required = false) Set<Priority> priorities,
            @RequestParam(name = "category", required = false) Set<IncidentCategory> categories,
            @RequestParam(name = "slaStatus", required = false) Set<SlaStatus> slaStatuses,
            @RequestParam(name = "serviceId", required = false) String serviceId,
            @RequestParam(name = "assigneeId", required = false) String assigneeId,
            @RequestParam(name = "reporterId", required = false) String reporterId,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fromDate,
            @RequestParam(name = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant toDate) {

        SearchService.IncidentSearchCriteria criteria = new SearchService.IncidentSearchCriteria(
                statuses, priorities, categories, slaStatuses, serviceId, assigneeId, reporterId, query,
                fromDate, toDate);

        List<Incident> results = searchService.search(criteria);
        return ResponseEntity.ok(mapToAdminViews(results));
    }

    @GetMapping("/incidents/{id}")
    public ResponseEntity<AdminIncidentDetailView> getIncidentDetails(
            @PathVariable("id") String id,
            Authentication authentication) {
        String adminId = currentUser.idOf(authentication);
        return ResponseEntity.ok(IncidentDtoMapper.toAdminDetailView(incidentDetailService.getDetail(adminId, id)));
    }

    @PostMapping("/incidents/{id}/assign")
    public ResponseEntity<AdminIncidentView> assignIncident(
            @PathVariable("id") String id,
            @Valid @RequestBody AssignRequest request,
            Authentication authentication) {
        String adminId = currentUser.idOf(authentication);
        Incident updated = incidentService.assign(id, adminId, request.technicianId());
        return ResponseEntity.ok(mapToAdminView(updated));
    }

    @PostMapping({"/incidents/{id}/override-priority", "/incidents/{id}/priority-override"})
    public ResponseEntity<AdminIncidentView> overridePriority(
            @PathVariable("id") String id,
            @Valid @RequestBody OverridePriorityRequest request,
            Authentication authentication) {
        String adminId = currentUser.idOf(authentication);
        Incident updated = incidentService.overridePriority(id, adminId, request.priority(), request.reason());
        return ResponseEntity.ok(mapToAdminView(updated));
    }

    @PostMapping("/incidents/{id}/close")
    public ResponseEntity<AdminIncidentView> closeIncident(
            @PathVariable("id") String id,
            @RequestBody(required = false) FeedbackRequest request,
            Authentication authentication) {
        String adminId = currentUser.idOf(authentication);
        Feedback feedback = null;
        if (request != null && request.rating() > 0) {
            feedback = new Feedback(request.rating(), request.comment(), clock.instant());
        }
        Incident updated = incidentService.close(id, adminId, feedback);
        return ResponseEntity.ok(mapToAdminView(updated));
    }

    @GetMapping("/triage-queue")
    public ResponseEntity<List<TriageQueueItemDto>> getTriageQueue() {
        return ResponseEntity.ok(incidentService.getUnifiedTriageQueue());
    }

    public record AcceptTriageSuggestionRequest(String category, String serviceId) {}

    @PostMapping("/triage/{id}/accept-suggestion")
    public ResponseEntity<AdminIncidentView> acceptTriageSuggestion(
            @PathVariable("id") String id,
            @RequestBody(required = false) AcceptTriageSuggestionRequest request,
            Authentication authentication) {
        String adminId = currentUser.idOf(authentication);
        // No category (no body, or none in it) means "accept the classifier's suggestion"; an unknown one is a 400.
        IncidentCategory cat = (request != null && request.category() != null)
                ? IncidentCategory.fromText(request.category())
                : null;
        String srv = (request != null) ? request.serviceId() : null;
        Incident updated = incidentService.acceptTriageSuggestion(id, adminId, cat, srv);
        return ResponseEntity.ok(mapToAdminView(updated));
    }

    /** Rejects a technician's wrong-domain request: the ticket goes back to that technician (ADR-029). */
    @PostMapping("/reassignments/{requestId}/reject")
    public ResponseEntity<AdminIncidentView> rejectReassignment(
            @PathVariable("requestId") String requestId,
            @Valid @RequestBody RejectReassignmentRequest request,
            Authentication authentication) {
        Incident updated = incidentService.rejectReassignment(requestId, currentUser.idOf(authentication), request.reason());
        return ResponseEntity.ok(mapToAdminView(updated));
    }

    /** IT services for the search filter's service dropdown (ADR-049); administrators only (class rule). */
    @GetMapping("/services")
    public ResponseEntity<List<ItServiceOptionDto>> listServices() {
        return ResponseEntity.ok(serviceCatalogService.listServices().stream()
                .map(ItServiceOptionDto::fromDomain)
                .toList());
    }

    @GetMapping("/technicians")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_LEAD')")
    public ResponseEntity<List<TechnicianSummaryDto>> getTechnicianRoster(Authentication authentication) {
        Map<String, Integer> carried = capacityService.carriedDuplicatesByTechnician();
        List<TechnicianSummaryDto> roster = userService.getRosterVisibleTo(currentUser.idOf(authentication)).stream()
                .map(t -> TechnicianSummaryDto.fromDomain(t, carried.getOrDefault(t.getId(), 0), clock.instant()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(roster);
    }

    /** Differences between the technicians' stored capacity slots and the tickets that name them (ADR-026). */
    @GetMapping("/capacity/reconcile")
    public ResponseEntity<CapacityReportView> getCapacityReport() {
        return ResponseEntity.ok(CapacityReportView.fromDomain(capacityService.audit()));
    }

    /** Repairs phantom and missing slots; orphaned duplicates are only reported (ADR-026). */
    @PostMapping("/capacity/reconcile")
    public ResponseEntity<CapacityRepairView> repairCapacity() {
        return ResponseEntity.ok(CapacityRepairView.fromDomain(capacityService.repair()));
    }

    /**
     * Switches off another administrator's two-step verification (a lost phone and no recovery codes); their sessions end
     * too (ADR-048). Resetting oneself is refused: that has its own path with the password and a code.
     */
    @PostMapping("/users/{userId}/mfa/reset")
    public ResponseEntity<Void> resetMfa(@PathVariable String userId, Authentication authentication) {
        mfaService.adminReset(currentUser.idOf(authentication), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{userId}/unlock")
    public ResponseEntity<Void> unlockUser(@PathVariable String userId) {
        userService.unlockUser(userId);
        return ResponseEntity.ok().build();
    }

    public record UpdateDesignationRequest(String designation) {}

    @PutMapping("/users/{userId}/designation")
    public ResponseEntity<Void> updateDesignation(
            @PathVariable String userId,
            @RequestBody UpdateDesignationRequest request) {
        if (request != null && request.designation() != null) {
            Designation des = Designation.fromText(request.designation());
            userService.updateEmployeeDesignation(userId, des);
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/assignment/strategies")
    public ResponseEntity<List<StrategyInfoDto>> getAssignmentStrategies() {
        AssignmentStrategyRegistry registry = incidentService.getAssignmentStrategyRegistry();
        String activeName = registry.getActiveStrategyName();
        List<StrategyInfoDto> dtos = registry.getAllStrategies().stream()
                .map(s -> new StrategyInfoDto(s.getStrategyName(), s.getDescription(), s.getStrategyName().equalsIgnoreCase(activeName)))
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PutMapping("/assignment/strategies/active")
    public ResponseEntity<StrategyInfoDto> setActiveAssignmentStrategy(
            @Valid @RequestBody SwitchStrategyRequest request) {
        AssignmentStrategyRegistry registry = incidentService.getAssignmentStrategyRegistry();
        registry.setActiveStrategy(request.strategyName());
        AssignmentStrategy active = registry.getActiveStrategy();
        return ResponseEntity.ok(new StrategyInfoDto(active.getStrategyName(), active.getDescription(), true));
    }

    @PostMapping("/incidents/{id}/reclassify")
    public ResponseEntity<AdminIncidentView> reclassifyIncident(
            @PathVariable("id") String id,
            @Valid @RequestBody ReclassifyRequest request,
            Authentication authentication) {
        String adminId = currentUser.idOf(authentication);
        Incident updated = incidentService.reclassify(id, adminId, request.category(), request.serviceId(), request.reason());
        return ResponseEntity.ok(mapToAdminView(updated));
    }

    @GetMapping("/incidents/{id}/duplicates")
    public ResponseEntity<List<AdminIncidentView>> getIncidentDuplicates(@PathVariable("id") String id) {
        List<Incident> duplicates = incidentService.getDuplicateDetectionService().findLinkedDuplicates(id);
        return ResponseEntity.ok(mapToAdminViews(duplicates));
    }

    private AdminIncidentView mapToAdminView(Incident incident) {
        return mapToAdminViews(List.of(incident)).get(0);
    }

    /** A list of tickets; the actor names of the whole list come from one lookup, not one per row (ADR-037). */
    private List<AdminIncidentView> mapToAdminViews(List<Incident> incidents) {
        Map<String, List<AuditRecord>> trails = new HashMap<>();
        incidents.forEach(i -> trails.put(i.getId(), auditService.getAuditTrailForIncident(i.getId())));
        Map<String, String> names = userService.namesById(IncidentActors.ofAll(incidents, i -> trails.get(i.getId())));
        return incidents.stream().map(incident -> {
            User reporter = userService.findById(incident.getReporterId()).orElse(null);
            User assignee = (incident.getAssigneeId() != null)
                    ? userService.findById(incident.getAssigneeId()).orElse(null)
                    : null;
            return IncidentDtoMapper.toAdminView(incident, reporter, assignee, trails.get(incident.getId()), names);
        }).collect(Collectors.toList());
    }
}
