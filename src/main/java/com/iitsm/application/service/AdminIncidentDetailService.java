package com.iitsm.application.service;

import com.iitsm.application.dto.AdminIncidentDetail;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.UserRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only application service assembling the full admin inspection view of a single incident.
 * Keeps related-entity lookups out of the web layer; access control is enforced by delegating
 * the incident load to {@link IncidentLifecycleService}.
 */
@Service
@Transactional(readOnly = true)
public class AdminIncidentDetailService {

    private final IncidentLifecycleService lifecycleService;
    private final UserRepository userRepository;
    private final ItServiceRepository itServiceRepository;
    private final SupportTeamRepository supportTeamRepository;
    private final AuditService auditService;

    public AdminIncidentDetailService(
            IncidentLifecycleService lifecycleService,
            UserRepository userRepository,
            ItServiceRepository itServiceRepository,
            SupportTeamRepository supportTeamRepository,
            AuditService auditService) {
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository cannot be null");
        this.itServiceRepository = Objects.requireNonNull(itServiceRepository, "itServiceRepository cannot be null");
        this.supportTeamRepository = Objects.requireNonNull(supportTeamRepository, "supportTeamRepository cannot be null");
        this.auditService = Objects.requireNonNull(auditService, "auditService cannot be null");
    }

    public AdminIncidentDetail getDetail(String actorIdOrEmail, String incidentId) {
        Incident incident = lifecycleService.getIncident(actorIdOrEmail, incidentId);
        User assignee = findUser(incident.getAssigneeId());
        ItService service = findService(incident.getServiceId());
        return new AdminIncidentDetail(
                incident,
                findUser(incident.getReporterId()),
                assignee,
                service,
                findSupportTeam(assignee, service),
                auditService.getAuditTrailForIncident(incident.getId()));
    }

    private User findUser(String userId) {
        return (userId == null) ? null : userRepository.findById(userId).orElse(null);
    }

    private ItService findService(String serviceId) {
        return (serviceId == null) ? null : itServiceRepository.findById(serviceId).orElse(null);
    }

    /** The assignee's own team when assigned; otherwise the team that owns the incident's IT service. */
    private SupportTeam findSupportTeam(User assignee, ItService service) {
        String assigneeTeamId = (assignee instanceof Technician technician) ? technician.getSupportTeamId() : null;
        String teamId = (assigneeTeamId != null)
                ? assigneeTeamId
                : Optional.ofNullable(service).map(ItService::getOwningTeamId).orElse(null);
        return (teamId == null) ? null : supportTeamRepository.findById(teamId).orElse(null);
    }
}
