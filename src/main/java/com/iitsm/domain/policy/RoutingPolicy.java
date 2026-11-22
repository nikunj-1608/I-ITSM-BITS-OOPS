package com.iitsm.domain.policy;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.SupportTeam;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Pattern: Policy. The one rule for which team owns a ticket (ADR-036, decision D17).
 *
 * <p>The classified <em>category</em> decides the team: the support team that handles the category. The IT service no
 * longer decides the team; it decides the business criticality and with it the priority and the SLA policy. Before this
 * rule the team came from the matched service, and a ticket that matched no service fell back to the "general" service
 * of the Service Desk, so a DNS ticket went to the Service Desk.
 */
public final class RoutingPolicy {

    /** The team that handles the category; when several do, the one with the smallest id, so the answer is stable. */
    public Optional<SupportTeam> teamHandling(IncidentCategory category, Collection<SupportTeam> teams) {
        if (category == null || teams == null) {
            return Optional.empty();
        }
        return teams.stream()
                .filter(team -> team.getHandledCategories().contains(category))
                .min(Comparator.comparing(SupportTeam::getId));
    }

    /**
     * The id of the team that owns a ticket of this category. When no team handles the category (a gap in the team
     * set-up, not a normal case) the owner of the ticket's service is the fallback, so a ticket is not left without a
     * team; without both there is none.
     */
    public Optional<String> owningTeamId(IncidentCategory category, ItService service, Collection<SupportTeam> teams) {
        Optional<String> byCategory = teamHandling(category, teams).map(SupportTeam::getId);
        if (byCategory.isPresent()) {
            return byCategory;
        }
        return Optional.ofNullable(service).map(ItService::getOwningTeamId);
    }
}
