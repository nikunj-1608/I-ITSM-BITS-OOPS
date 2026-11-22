package com.iitsm.application.service;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.policy.RoutingPolicy;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.strategy.assignment.CandidateFilter;
import com.iitsm.domain.strategy.assignment.RoutedCandidates;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Pattern: Facade over {@link RoutingPolicy} and {@link CandidateFilter}: the single place that decides which team owns
 * a ticket and which technicians may take it (ADR-036, decisions D17 and D18).
 *
 * <p>Used by every caller that assigns or reroutes: the intake and the dispatcher (through
 * {@link TicketDispatchService}), the administrator's reclassify reroute and accept-suggestion, and the access rules that
 * need the owning team ({@link IncidentLifecycleService}, the team queue, the admin detail view).
 *
 * <ul>
 *   <li>The classified category decides the owning team; the service decides criticality and the SLA policy.</li>
 *   <li>Candidates are the owning team's technicians with free capacity; when none is free, technicians of other teams
 *       who have the category's skill; when none of those is free either, nobody: the ticket waits, and the SLA
 *       escalation covers a long wait. An unskilled technician of another team never gets the ticket.</li>
 * </ul>
 */
@Service
public class TicketRouter {

    private final SupportTeamRepository supportTeamRepository;
    private final ItServiceRepository itServiceRepository;
    private final CandidateFilter candidateFilter;
    private final RoutingPolicy routingPolicy;

    public TicketRouter(
            SupportTeamRepository supportTeamRepository,
            ItServiceRepository itServiceRepository,
            CandidateFilter candidateFilter,
            RoutingPolicy routingPolicy) {
        this.supportTeamRepository = Objects.requireNonNull(supportTeamRepository, "supportTeamRepository cannot be null");
        this.itServiceRepository = Objects.requireNonNull(itServiceRepository, "itServiceRepository cannot be null");
        this.candidateFilter = Objects.requireNonNull(candidateFilter, "candidateFilter cannot be null");
        this.routingPolicy = Objects.requireNonNull(routingPolicy, "routingPolicy cannot be null");
    }

    /** The team that owns tickets of this category; {@code service} is only the fallback when no team handles it. */
    public Optional<String> owningTeamId(IncidentCategory category, ItService service) {
        if (category == null) {
            return Optional.ofNullable(service).map(ItService::getOwningTeamId);
        }
        return routingPolicy.owningTeamId(category, service, supportTeamRepository.findByHandledCategory(category));
    }

    /** The team that owns the ticket: by its category; its service is looked up only when the category has no team. */
    public Optional<String> owningTeamId(Incident incident) {
        Optional<String> byCategory = owningTeamId(incident.getCategory(), null);
        if (byCategory.isPresent() || incident.getServiceId() == null) {
            return byCategory;
        }
        return itServiceRepository.findById(incident.getServiceId()).map(ItService::getOwningTeamId);
    }

    /** The categories the team handles; empty for an unknown team. */
    public Set<IncidentCategory> categoriesHandledBy(String teamId) {
        return supportTeamRepository.findById(teamId).map(SupportTeam::getHandledCategories).orElse(Set.of());
    }

    /** The technicians who may take the ticket now, out of those available: see the class description. */
    public List<Technician> candidates(Incident incident, List<Technician> available) {
        return route(incident, available).technicians();
    }

    /**
     * Same rule for a ticket that is about to change category (the administrator's reclassify): the team and the category
     * are the new ones, so the caller can read everything before it changes the ticket.
     */
    public List<Technician> candidates(
            IncidentCategory category, String owningTeamId, List<Technician> available, Set<String> excludedIds) {
        return route(category, owningTeamId, available, excludedIds).technicians();
    }

    /** The candidates of {@link #candidates(Incident, List)} with the pool they came from (ADR-044). */
    public RoutedCandidates route(Incident incident, List<Technician> available) {
        return route(incident.getCategory(), owningTeamId(incident).orElse(null), available,
                withReporter(incident.getExcludedTechnicianIds(), incident.getReporterId()));
    }

    /** A technician never works a ticket they raised themselves: the reporter is always excluded from the candidates. */
    public static Set<String> withReporter(Set<String> excludedIds, String reporterId) {
        Set<String> result = new HashSet<>(excludedIds == null ? Set.of() : excludedIds);
        if (reporterId != null) {
            result.add(reporterId);
        }
        return result;
    }

    /** The candidates of {@link #candidates(IncidentCategory, String, List, Set)} with the pool they came from. */
    public RoutedCandidates route(
            IncidentCategory category, String owningTeamId, List<Technician> available, Set<String> excludedIds) {
        // The filter already keeps only technicians with free capacity
        return candidateFilter.route(available, owningTeamId, category, excludedIds);
    }
}
