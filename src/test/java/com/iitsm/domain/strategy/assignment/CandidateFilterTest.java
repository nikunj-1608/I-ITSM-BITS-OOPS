package com.iitsm.domain.strategy.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.Technician;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase G (ADR-036, decision D18): the owning team's technicians come first; technicians of other
 * teams are candidates only when they have the category's skill; an unskilled technician of another team never is.
 */
class CandidateFilterTest {

    private final CandidateFilter filter = new CandidateFilter();

    private static Technician technician(String id, String team, IncidentCategory skillCategory) {
        return new Technician(id, "Tech " + id, new Email(id.toLowerCase() + "@test.com"), "hash",
                Set.of(new Skill("Skill of " + id, skillCategory)), 5, 2, team);
    }

    private static Technician full(Technician technician) {
        technician.assignIncident("FILL-1");
        technician.assignIncident("FILL-2");
        assertThat(technician.hasCapacity()).isFalse();
        return technician;
    }

    private final Technician netOne = technician("NET-1", "TEAM-NET", IncidentCategory.NETWORK);
    private final Technician netTwo = technician("NET-2", "TEAM-NET", IncidentCategory.NETWORK);
    private final Technician securitySkilledInNetwork = technician("SEC-NET", "TEAM-SEC", IncidentCategory.NETWORK);
    private final Technician securityOnly = technician("SEC-1", "TEAM-SEC", IncidentCategory.SECURITY);

    @Test
    @DisplayName("The owning team's technicians are offered first, and only they while one has capacity")
    void owningTeamFirst() {
        List<Technician> candidates = filter.filterCandidates(
                List.of(securitySkilledInNetwork, netOne, netTwo), "TEAM-NET", IncidentCategory.NETWORK, Set.of());

        assertThat(candidates).containsExactlyInAnyOrder(netOne, netTwo);
    }

    @Test
    @DisplayName("D18: when the owning team is full, technicians of other teams with the category skill are offered")
    void skilledOverflow() {
        List<Technician> candidates = filter.filterCandidates(
                List.of(full(netOne), full(netTwo), securitySkilledInNetwork, securityOnly),
                "TEAM-NET", IncidentCategory.NETWORK, Set.of());

        assertThat(candidates).containsExactly(securitySkilledInNetwork);
    }

    @Test
    @DisplayName("D18: an unskilled technician of another team is never offered the ticket")
    void unskilledNeverOffered() {
        List<Technician> candidates = filter.filterCandidates(
                List.of(full(netOne), securityOnly), "TEAM-NET", IncidentCategory.NETWORK, Set.of());

        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("An excluded technician is not offered, in the team or as overflow")
    void exclusionsApplyEverywhere() {
        assertThat(filter.filterCandidates(List.of(netOne, netTwo), "TEAM-NET", IncidentCategory.NETWORK, Set.of("NET-1")))
                .containsExactly(netTwo);
        assertThat(filter.filterCandidates(List.of(full(netOne), securitySkilledInNetwork), "TEAM-NET",
                IncidentCategory.NETWORK, Set.of("SEC-NET"))).isEmpty();
    }

    @Test
    @DisplayName("Team ids are compared without regard to case")
    void teamIdsIgnoreCase() {
        assertThat(filter.filterCandidates(List.of(netOne), "team-net", IncidentCategory.NETWORK, Set.of()))
                .containsExactly(netOne);
    }

    @Test
    @DisplayName("Without an owning team only skilled technicians qualify; without a category nobody from another team does")
    void missingTeamOrCategory() {
        assertThat(filter.filterCandidates(List.of(netOne, securityOnly), null, IncidentCategory.NETWORK, Set.of()))
                .containsExactly(netOne);
        assertThat(filter.filterCandidates(List.of(securitySkilledInNetwork), "TEAM-NET", null, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("No technicians, no candidates")
    void emptyInput() {
        assertThat(filter.filterCandidates(List.of(), "TEAM-NET", IncidentCategory.NETWORK, Set.of())).isEmpty();
        assertThat(filter.filterCandidates(null, "TEAM-NET", IncidentCategory.NETWORK, null)).isEmpty();
    }
}
