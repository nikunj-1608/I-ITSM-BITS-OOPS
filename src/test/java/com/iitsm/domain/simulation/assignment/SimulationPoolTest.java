package com.iitsm.domain.simulation.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.Technician;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The technician pool of an offline comparison and its profiles (ADR-044, Phase L3). */
class SimulationPoolTest {

    private static TechnicianProfile profile(String id, String team, IncidentCategory skill) {
        return new TechnicianProfile(id, team, 4, 3, skill == null ? Set.of() : Set.of(new Skill("S", skill)));
    }

    @Test
    @DisplayName("a profile copies a live technician by value and builds fresh, empty technicians")
    void profileCopiesByValue() {
        Technician live = new Technician("TECH-9", "Live Person", new Email("live@test.com"), "hash",
                Set.of(new Skill("BGP", IncidentCategory.NETWORK)), 7, 6, "TEAM-NET");
        live.assignIncident("INC-1");

        TechnicianProfile profile = TechnicianProfile.of(live);
        Technician copy = profile.toTechnician();

        assertThat(profile.id()).isEqualTo("TECH-9");
        assertThat(profile.supportTeamId()).isEqualTo("TEAM-NET");
        assertThat(profile.experienceYears()).isEqualTo(7);
        assertThat(profile.maxCapacity()).isEqualTo(6);
        assertThat(profile.hasCategorySkill(IncidentCategory.NETWORK)).isTrue();
        assertThat(profile.hasCategorySkill(IncidentCategory.CLOUD)).isFalse();
        assertThat(copy).isNotSameAs(live);
        assertThat(copy.getActiveIncidentCount()).as("the live load is not copied").isZero();
        assertThat(live.getActiveIncidentCount()).as("the live technician is untouched").isEqualTo(1);
        assertThat(copy.getEmail().value()).endsWith("@simulation.invalid");
    }

    @Test
    @DisplayName("profile rules: an id, no negative experience, a positive capacity; skills are copied")
    void profileRules() {
        assertThatThrownBy(() -> new TechnicianProfile(" ", "T", 1, 1, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TechnicianProfile("A", "T", -1, 1, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TechnicianProfile("A", "T", 1, 0, Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a category is servable when its owning team has a member or any technician has the skill")
    void servableCategories() {
        SimulationPool pool = new SimulationPool(
                List.of(profile("A", "TEAM-1", null), profile("B", "TEAM-2", IncidentCategory.CLOUD)),
                Map.of(IncidentCategory.NETWORK, "TEAM-1", IncidentCategory.DATABASE, "TEAM-9"));

        assertThat(pool.servableCategories()).containsExactlyInAnyOrder(IncidentCategory.NETWORK, IncidentCategory.CLOUD);
        assertThat(pool.owningTeamOf(IncidentCategory.NETWORK)).isEqualTo("TEAM-1");
        assertThat(pool.owningTeamOf(IncidentCategory.STORAGE)).isNull();
    }

    @Test
    @DisplayName("a pool needs technicians with unique ids and some servable category")
    void poolRules() {
        assertThatThrownBy(() -> new SimulationPool(List.of(), Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimulationPool(List.of(profile("A", "T", IncidentCategory.CLOUD),
                profile("A", "T", null)), Map.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new SimulationPool(List.of(profile("A", "T", null)), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no category");
    }

    @Test
    @DisplayName("fromRoster gives empty (the caller uses the synthetic pool) for an empty or useless roster")
    void fromRoster() {
        assertThat(SimulationPool.fromRoster(List.of(), Map.of())).isEmpty();
        assertThat(SimulationPool.fromRoster(List.of(profile("A", "T", null)), Map.of())).isEmpty();
        assertThat(SimulationPool.fromRoster(List.of(profile("A", "T", IncidentCategory.CLOUD)), Map.of())).isPresent();
    }

    @Test
    @DisplayName("the pool is immutable: later changes to the source list or map do not reach it")
    void immutable() {
        List<TechnicianProfile> source = new ArrayList<>(List.of(profile("A", "T", IncidentCategory.CLOUD)));
        SimulationPool pool = new SimulationPool(source, Map.of());
        source.add(profile("B", "T", null));

        assertThat(pool.technicians()).hasSize(1);
        assertThatThrownBy(() -> pool.technicians().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> pool.owningTeamByCategory().put(IncidentCategory.CLOUD, "X")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the synthetic pool has three teams of four, serves every category, and some members lack a team skill")
    void syntheticDefault() {
        SimulationPool pool = SimulationPool.syntheticDefault();

        assertThat(pool.technicians()).hasSize(12);
        assertThat(pool.servableCategories()).isEqualTo(EnumSet.allOf(IncidentCategory.class));
        assertThat(pool.technicians().stream().map(TechnicianProfile::supportTeamId).distinct()).hasSize(3);
        for (IncidentCategory category : IncidentCategory.values()) {
            String team = pool.owningTeamOf(category);
            long skilled = pool.technicians().stream().filter(t -> team.equals(t.supportTeamId()))
                    .filter(t -> t.hasCategorySkill(category)).count();
            assertThat(skilled).as("skilled members of the owner of %s", category).isBetween(1L, 3L);
        }
    }
}
