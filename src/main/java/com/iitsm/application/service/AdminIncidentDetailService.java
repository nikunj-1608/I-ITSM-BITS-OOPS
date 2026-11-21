package com.iitsm.application.service;

import com.iitsm.application.dto.AdminIncidentDetail;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.AssignmentDecisionRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.assignment.AssignmentDecision;
import com.iitsm.domain.strategy.assignment.CandidateScore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only application service assembling the full admin inspection view of a single incident.
 * Keeps related-entity lookups out of the web layer; access control is enforced by delegating
 * the incident load to {@link IncidentLifecycleService}.
 *
 * <p>Since ADR-044 it also reads the newest {@value #DECISIONS_SHOWN} assignment decisions of the ticket; the names of
 * every person they mention come from the same single batch lookup as the audit actors (ADR-037).
 */
@Service
@Transactional(readOnly = true)
public class AdminIncidentDetailService {

    private final IncidentLifecycleService lifecycleService;
    private final UserRepository userRepository;
    private final ItServiceRepository itServiceRepository;
    private final SupportTeamRepository supportTeamRepository;
    private final AuditService auditService;
    private final TicketRouter ticketRouter;
    private final AssignmentDecisionRepository assignmentDecisionRepository;

    /** How many assignment decisions the dialog shows, newest first. */
    public static final int DECISIONS_SHOWN = 5;

    public AdminIncidentDetailService(
            IncidentLifecycleService lifecycleService,
            UserRepository userRepository,
            ItServiceRepository itServiceRepository,
            SupportTeamRepository supportTeamRepository,
            AuditService auditService,
            TicketRouter ticketRouter,
            AssignmentDecisionRepository assignmentDecisionRepository) {
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository cannot be null");
        this.itServiceRepository = Objects.requireNonNull(itServiceRepository, "itServiceRepository cannot be null");
        this.supportTeamRepository = Objects.requireNonNull(supportTeamRepository, "supportTeamRepository cannot be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService cannot be null");
        this.ticketRouter = Objects.requireNonNull(ticketRouter, "ticketRouter cannot be null");
        this.assignmentDecisionRepository = Objects.requireNonNull(assignmentDecisionRepository,
                "assignmentDecisionRepository cannot be null");
    }

    public AdminIncidentDetail getDetail(String actorIdOrEmail, String incidentId) {
        Incident incident = lifecycleService.getIncident(actorIdOrEmail, incidentId);
        User assignee = findUser(incident.getAssigneeId());
        ItService service = findService(incident.getServiceId());
        java.util.List<com.iitsm.domain.model.AuditRecord> trail = auditService.getAuditTrailForIncident(incident.getId());
        List<AssignmentDecision> decisions = assignmentDecisionRepository.findNewestByIncidentId(incident.getId(), DECISIONS_SHOWN);
        Set<String> people = new LinkedHashSet<>(IncidentActors.of(incident, trail));
        people.addAll(peopleIn(decisions));
        return new AdminIncidentDetail(
                incident,
                findUser(incident.getReporterId()),
                assignee,
                service,
                findSupportTeam(assignee, incident, service),
                trail,
                ActorNames.of(userRepository, people),
                decisions);
    }

    /** Everyone the decisions mention: the chosen technicians, the actors and every ranked candidate. */
    private static Set<String> peopleIn(List<AssignmentDecision> decisions) {
        Set<String> ids = new LinkedHashSet<>();
        for (AssignmentDecision decision : decisions) {
            ids.add(decision.chosenTechnicianId());
            ids.add(decision.actorId());
            decision.candidates().stream().map(CandidateScore::technicianId).forEach(ids::add);
        }
        return ids;
    }

    private User findUser(String userId) {
        return (userId == null) ? null : userRepository.findById(userId).orElse(null);
    }

    private ItService findService(String serviceId) {
        return (serviceId == null) ? null : itServiceRepository.findById(serviceId).orElse(null);
    }

    /** The assignee's own team when assigned; otherwise the team that handles the incident's category (ADR-036). */
    private SupportTeam findSupportTeam(User assignee, Incident incident, ItService service) {
        String assigneeTeamId = (assignee instanceof Technician technician) ? technician.getSupportTeamId() : null;
        String teamId = (assigneeTeamId != null)
                ? assigneeTeamId
                : ticketRouter.owningTeamId(incident.getCategory(), service).orElse(null);
        return (teamId == null) ? null : supportTeamRepository.findById(teamId).orElse(null);
    }
}
