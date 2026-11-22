package com.iitsm.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.SupportTeam;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase G (ADR-036, decision D17): the classified category decides which team owns a ticket; the
 * service is only a fallback when no team handles the category.
 */
class RoutingPolicyTest {

    private final RoutingPolicy policy = new RoutingPolicy();

    private static SupportTeam team(String id, IncidentCategory... categories) {
        return new SupportTeam(id, "Team " + id, Set.of(categories), "LEAD-" + id);
    }

    private static ItService serviceOwnedBy(String teamId) {
        ItService service = mock(ItService.class);
        when(service.getOwningTeamId()).thenReturn(teamId);
        return service;
    }

    private final List<SupportTeam> teams = List.of(
            team("TEAM-DESK", IncidentCategory.HARDWARE),
            team("TEAM-NET", IncidentCategory.NETWORK),
            team("TEAM-DB", IncidentCategory.DATABASE, IncidentCategory.STORAGE));

    @Test
    @DisplayName("D17: the team that handles the category owns the ticket")
    void categoryDecidesTheTeam() {
        assertThat(policy.owningTeamId(IncidentCategory.NETWORK, null, teams)).contains("TEAM-NET");
        assertThat(policy.owningTeamId(IncidentCategory.STORAGE, null, teams)).contains("TEAM-DB");
    }

    @Test
    @DisplayName("D17: a service owned by another team does not change the team")
    void theServiceOwnerDoesNotDecide() {
        assertThat(policy.owningTeamId(IncidentCategory.NETWORK, serviceOwnedBy("TEAM-DESK"), teams))
                .contains("TEAM-NET");
    }

    @Test
    @DisplayName("When no team handles the category the service's owner is the fallback, not a missing team")
    void serviceOwnerIsTheFallbackOnly() {
        assertThat(policy.owningTeamId(IncidentCategory.CLOUD, serviceOwnedBy("TEAM-DESK"), teams)).contains("TEAM-DESK");
        assertThat(policy.owningTeamId(IncidentCategory.CLOUD, null, teams)).isEmpty();
    }

    @Test
    @DisplayName("When several teams handle a category the one with the smallest id is chosen, every time")
    void severalTeamsGiveAStableAnswer() {
        List<SupportTeam> both = List.of(
                team("TEAM-B", IncidentCategory.NETWORK), team("TEAM-A", IncidentCategory.NETWORK));

        assertThat(policy.teamHandling(IncidentCategory.NETWORK, both)).map(SupportTeam::getId).contains("TEAM-A");
    }

    @Test
    @DisplayName("A missing category or an empty team list gives no team")
    void missingInputsGiveNoTeam() {
        assertThat(policy.teamHandling(null, teams)).isEmpty();
        assertThat(policy.teamHandling(IncidentCategory.NETWORK, null)).isEmpty();
        assertThat(policy.teamHandling(IncidentCategory.NETWORK, List.of())).isEmpty();
    }
}
