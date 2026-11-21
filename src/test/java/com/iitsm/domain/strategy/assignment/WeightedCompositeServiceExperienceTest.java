package com.iitsm.domain.strategy.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The weighted composite with the service-experience factor (ADR-044, Phase L4): off means exactly the historic strategy,
 * on adds one lookup per ranking through the port, shows up in the breakdown, and never changes a ranking where nobody has
 * history.
 */
class WeightedCompositeServiceExperienceTest {

    /** A source that counts its calls and remembers what it was asked. */
    private static final class Recording implements ServiceExperienceSource {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> services = new ArrayList<>();
        final List<Collection<String>> asked = new ArrayList<>();
        final Map<String, Integer> answer;

        Recording(Map<String, Integer> answer) {
            this.answer = answer;
        }

        @Override
        public Map<String, Integer> resolvedTicketCounts(String serviceId, Collection<String> technicianIds) {
            calls.incrementAndGet();
            services.add(serviceId);
            asked.add(List.copyOf(technicianIds));
            return answer;
        }
    }

    private final Incident ticket = AssignmentGrid.incidents().get("NET_P3");
    private final List<Technician> tie = AssignmentGrid.candidateLists().get("TIE");

    @Test
    @DisplayName("a zero weight is the historic composite and never asks for experience")
    void zeroWeightIsTheHistoricStrategy() {
        ServiceExperienceSource mustNotBeAsked = (service, ids) -> {
            throw new AssertionError("a zero weight must never ask for experience");
        };
        WeightedCompositeStrategy off = WeightedCompositeStrategy.withServiceExperience(mustNotBeAsked, 0.0, 5);
        WeightedCompositeStrategy historic = new WeightedCompositeStrategy();

        for (Incident incident : AssignmentGrid.incidents().values()) {
            for (List<Technician> candidates : AssignmentGrid.candidateLists().values()) {
                assertThat(off.recommend(incident, candidates)).isEqualTo(historic.recommend(incident, candidates));
            }
        }
    }

    @Test
    @DisplayName("one lookup per ranking, with the ticket's service and every candidate's id")
    void oneLookupPerRanking() {
        Recording source = new Recording(Map.of());
        WeightedCompositeStrategy composite = WeightedCompositeStrategy.withServiceExperience(source, 0.10, 5);

        composite.recommend(ticket, tie);

        assertThat(source.calls).hasValue(1);
        assertThat(source.services).containsExactly("SVC-TEST");
        assertThat(source.asked.get(0)).containsExactlyInAnyOrder("T-NET-A", "T-NET-B");
        composite.rank(ticket, tie);
        assertThat(source.calls).hasValue(2);
    }

    @Test
    @DisplayName("no lookup for a ticket without a specific service")
    void noLookupWithoutAService() {
        Recording source = new Recording(Map.of("T-NET-A", 3));
        WeightedCompositeStrategy composite = WeightedCompositeStrategy.withServiceExperience(source, 0.10, 5);

        composite.recommend(AssignmentGrid.incidents().get("RAW_VAGUE"), List.of(AssignmentGrid.technicians().get("T-JUN")));
        composite.recommend(generalServiceTicket(), tie);

        assertThat(source.calls).hasValue(0);
    }

    private static Incident generalServiceTicket() {
        Incident incident = new Incident("INC-GEN", "Printer queue stuck for everybody",
                "A ticket that matched no specific IT service at all.", "EMP-1", AssignmentGrid.NOW);
        incident.classify(com.iitsm.domain.model.IncidentCategory.HARDWARE, "SRV-GEN-001",
                new com.iitsm.domain.model.PriorityDecision(com.iitsm.domain.model.Priority.P3, 0.4, List.of(), List.of(),
                        AssignmentGrid.NOW, null, null),
                com.iitsm.domain.model.SlaPolicy.defaultFor(com.iitsm.domain.model.Priority.P3), AssignmentGrid.NOW);
        return incident;
    }

    @Test
    @DisplayName("the default composite (no factor) never asks anybody either")
    void defaultCompositeHasNoSource() {
        assertThat(new WeightedCompositeStrategy().rank(ticket, tie)).extracting(c -> c.factorScores().size()).containsOnly(3);
    }

    @Test
    @DisplayName("an experienced technician wins an otherwise exact tie, and the breakdown shows the factor and its weight")
    void experienceBreaksTheTie() {
        WeightedCompositeStrategy plain = new WeightedCompositeStrategy();
        WeightedCompositeStrategy withFactor = WeightedCompositeStrategy.withServiceExperience(new Recording(Map.of("T-NET-B", 5)), 0.10, 5);

        assertThat(plain.recommend(ticket, tie).technician().getId()).as("the first of equal candidates").isEqualTo("T-NET-A");
        AssignmentRecommendation chosen = withFactor.recommend(ticket, tie);

        assertThat(chosen.technician().getId()).isEqualTo("T-NET-B");
        CandidateScore top = chosen.ranking().get(0);
        assertThat(top.factorScores()).extracting(FactorScore::factorKey)
                .containsExactly("SKILL_MATCH", "LOAD", "EXPERIENCE", "SERVICE_EXPERIENCE");
        FactorScore service = top.factorScores().get(3);
        assertThat(service.rawScore()).isEqualTo(1.0);
        assertThat(service.weight()).isEqualTo(0.10);
        assertThat(service.contribution()).isEqualTo(0.10);
        assertThat(service.detail()).contains("5 ticket(s)");
        assertThat(chosen.rationale()).contains("Service: 1.00 * 0.10");
        assertThat(top.total()).isCloseTo(top.factorScores().stream().mapToDouble(FactorScore::contribution).sum(),
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    @DisplayName("when nobody has history the factor changes no ranking: same order as the historic composite everywhere")
    void noHistoryChangesNothing() {
        WeightedCompositeStrategy withFactor = WeightedCompositeStrategy.withServiceExperience(ServiceExperienceSource.none(), 0.10, 5);
        WeightedCompositeStrategy historic = new WeightedCompositeStrategy();

        for (Incident incident : AssignmentGrid.incidents().values()) {
            for (List<Technician> candidates : AssignmentGrid.candidateLists().values()) {
                assertThat(withFactor.rank(incident, candidates)).extracting(CandidateScore::technicianId)
                        .as("%s", incident.getId()).containsExactlyElementsOf(
                                historic.rank(incident, candidates).stream().map(CandidateScore::technicianId).toList());
            }
        }
    }

    @Test
    @DisplayName("equal history for everyone changes no ranking either")
    void equalHistoryChangesNothing() {
        Recording everyoneHasThree = new Recording(Map.of("T-NET-A", 3, "T-NET-B", 3, "T-APP", 3, "T-JUN", 3, "T-VET", 3,
                "T-VET2", 3, "T-OLD", 3, "T-NONE", 3));
        WeightedCompositeStrategy withFactor = WeightedCompositeStrategy.withServiceExperience(everyoneHasThree, 0.10, 5);
        WeightedCompositeStrategy historic = new WeightedCompositeStrategy();

        for (List<Technician> candidates : AssignmentGrid.candidateLists().values()) {
            assertThat(withFactor.rank(ticket, candidates).stream().map(CandidateScore::technicianId).toList())
                    .isEqualTo(historic.rank(ticket, candidates).stream().map(CandidateScore::technicianId).toList());
        }
    }

    @Test
    @DisplayName("rank agrees with recommend, including ties, with the factor on")
    void rankAgreesWithRecommend() {
        WeightedCompositeStrategy composite = WeightedCompositeStrategy.withServiceExperience(
                new Recording(Map.of("T-APP", 2, "T-OLD", 5)), 0.10, 5);

        for (Incident incident : AssignmentGrid.incidents().values()) {
            for (List<Technician> candidates : AssignmentGrid.candidateLists().values()) {
                AssignmentRecommendation chosen = composite.recommend(incident, candidates);
                assertThat(composite.rank(incident, candidates).get(0).technicianId()).isEqualTo(chosen.technician().getId());
                assertThat(chosen.ranking()).isEqualTo(composite.rank(incident, candidates));
            }
        }
    }

    @Test
    @DisplayName("a candidate the source does not mention has 0 experience")
    void unmentionedCandidatesHaveZero() {
        WeightedCompositeStrategy composite = WeightedCompositeStrategy.withServiceExperience(new Recording(Map.of("T-NET-B", 2)), 0.10, 5);

        List<CandidateScore> ranking = composite.rank(ticket, tie);

        assertThat(ranking.get(0).technicianId()).isEqualTo("T-NET-B");
        assertThat(ranking.get(1).factorScores().get(3).rawScore()).isZero();
        assertThat(ranking.get(0).factorScores().get(3).rawScore()).isEqualTo(0.4);
    }

    @Test
    @DisplayName("bindExperience gives a copy that reads another source and keeps the factors, weights and name")
    void bindExperience() {
        Recording original = new Recording(Map.of("T-NET-A", 5));
        Recording other = new Recording(Map.of("T-NET-B", 5));
        WeightedCompositeStrategy composite = WeightedCompositeStrategy.withServiceExperience(original, 0.10, 5);

        AssignmentStrategy bound = composite.bindExperience(other);

        assertThat(bound).isNotSameAs(composite).isInstanceOf(ServiceExperienceBindable.class);
        assertThat(bound.getStrategyName()).isEqualTo("WEIGHTED_COMPOSITE");
        assertThat(bound.recommend(ticket, tie).technician().getId()).isEqualTo("T-NET-B");
        assertThat(composite.recommend(ticket, tie).technician().getId()).as("the original is unchanged").isEqualTo("T-NET-A");
        assertThat(other.calls).hasValue(1);
        assertThat(original.calls).hasValue(1);
        assertThat(bound.rank(ticket, tie).get(0).factorScores()).hasSize(4);
    }

    @Test
    @DisplayName("named gives a variant under another name that compares side by side and keeps everything else")
    void namedVariant() {
        WeightedCompositeStrategy variant = WeightedCompositeStrategy.withServiceExperience(new Recording(Map.of()), 0.20, 5).named("WC_W20");

        assertThat(variant.getStrategyName()).isEqualTo("WC_W20");
        assertThat(variant.recommend(ticket, tie).strategyName()).isEqualTo("WC_W20");
        assertThat(variant.bindExperience(ServiceExperienceSource.none()).getStrategyName()).isEqualTo("WC_W20");
        assertThat(variant.rank(ticket, tie).get(0).factorScores().get(3).weight()).isEqualTo(0.20);
    }

    @Test
    @DisplayName("the weight is bounded: 0 up to 0.5; a source is required")
    void weightBounds() {
        assertThat(WeightedCompositeStrategy.withServiceExperience(ServiceExperienceSource.none(), 0.5, 5)).isNotNull();
        for (double bad : new double[] {-0.01, 0.51, 1.0, Double.NaN}) {
            assertThatThrownBy(() -> WeightedCompositeStrategy.withServiceExperience(ServiceExperienceSource.none(), bad, 5))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0.5");
        }
        assertThatThrownBy(() -> WeightedCompositeStrategy.withServiceExperience(null, 0.1, 5)).isInstanceOf(NullPointerException.class);
        assertThat(WeightedCompositeStrategy.MAX_SERVICE_EXPERIENCE_WEIGHT).isEqualTo(0.5);
    }

    @Test
    @DisplayName("the registry takes a configured default strategy, and refuses one with another name")
    void registryWithAConfiguredDefault() {
        WeightedCompositeStrategy configured = WeightedCompositeStrategy.withServiceExperience(ServiceExperienceSource.none(), 0.10, 5);
        AssignmentStrategyRegistry registry = new AssignmentStrategyRegistry(configured);

        assertThat(registry.getActiveStrategy()).isSameAs(configured);
        assertThat(registry.getAllStrategies()).hasSize(5);
        assertThat(new AssignmentStrategyRegistry().getActiveStrategy().rank(ticket, tie).get(0).factorScores()).hasSize(3);
        assertThatThrownBy(() -> new AssignmentStrategyRegistry(new RoundRobinStrategy())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentStrategyRegistry(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentStrategyRegistry(configured.named("OTHER"))).isInstanceOf(IllegalArgumentException.class);
    }
}
