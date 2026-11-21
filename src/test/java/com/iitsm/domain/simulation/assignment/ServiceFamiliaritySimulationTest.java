package com.iitsm.domain.simulation.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.strategy.assignment.AssignmentRecommendation;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.ServiceExperienceBindable;
import com.iitsm.domain.strategy.assignment.ServiceExperienceSource;
import com.iitsm.domain.strategy.assignment.WeightedCompositeStrategy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The service dimension of the offline comparison (ADR-044, Phase L4): tickets that belong to IT services, technicians who get
 * faster at a service they have resolved before, and the replay's in-memory experience that a service-experience strategy is
 * re-bound to, so no simulation ever reads the real database.
 */
class ServiceFamiliaritySimulationTest {

    private static final IncidentCategory NET = IncidentCategory.NETWORK;
    private static final long SIX_HOURS = Duration.ofHours(6).toMillis();

    /** A strategy that records what its experience source says at every decision, and picks the first candidate. */
    private static final class Probe implements AssignmentStrategy, ServiceExperienceBindable {
        final ServiceExperienceSource source;
        final List<Map<String, Integer>> seen;

        Probe(ServiceExperienceSource source, List<Map<String, Integer>> seen) {
            this.source = source;
            this.seen = seen;
        }

        @Override
        public AssignmentRecommendation recommend(Incident incident, List<Technician> candidates) {
            Collection<String> ids = candidates.stream().map(Technician::getId).toList();
            seen.add(new HashMap<>(source.resolvedTicketCounts(incident.getServiceId(), ids)));
            return new AssignmentRecommendation(candidates.get(0), 0.5, "PROBE", "first");
        }

        @Override
        public AssignmentStrategy bindExperience(ServiceExperienceSource other) {
            return new Probe(other, seen);
        }

        @Override
        public String getStrategyName() {
            return "PROBE";
        }

        @Override
        public String getDescription() {
            return "records the experience it is shown";
        }
    }

    private static SyntheticTicket ticket(int index, long arrivalMillis, String service) {
        return new SyntheticTicket(index, Duration.ofMillis(arrivalMillis), Priority.P3, NET, "Replay ticket " + index,
                "A hand made ticket for the service familiarity tests " + index + ".", 1.0, service);
    }

    private ReplayResult replay(WorkTimeModel model, AssignmentStrategy strategy, SyntheticTicket... tickets) {
        return new StrategyReplay(SimulationFixtures.singleTechnicianPool(2, 5, NET), model)
                .run(strategy, SimulationFixtures.prepared(List.of(tickets)), SimulationFixtures.START);
    }

    @Test
    @DisplayName("with familiarity the replay shows a strategy the tickets resolved so far: 0, then 1 for the same service, 0 for another")
    void replayExposesWhatWasResolved() {
        List<Map<String, Integer>> seen = new ArrayList<>();

        replay(WorkTimeModel.withServiceFamiliarity(0.5, 5), new Probe(ServiceExperienceSource.none(), seen),
                ticket(0, 0, "SIM-NETWORK-1"), ticket(1, SIX_HOURS, "SIM-NETWORK-1"), ticket(2, 2 * SIX_HOURS, "SIM-NETWORK-2"),
                ticket(3, 3 * SIX_HOURS, "SIM-NETWORK-1"));

        assertThat(seen).containsExactly(Map.of("T-1", 0), Map.of("T-1", 1), Map.of("T-1", 0), Map.of("T-1", 2));
    }

    @Test
    @DisplayName("an experience counts when the ticket is resolved, not when it is assigned")
    void countsAtCompletion() {
        List<Map<String, Integer>> seen = new ArrayList<>();

        // the second ticket arrives while the first is still being worked on
        replay(WorkTimeModel.withServiceFamiliarity(0.5, 5), new Probe(ServiceExperienceSource.none(), seen),
                ticket(0, 0, "SIM-NETWORK-1"), ticket(1, 60_000, "SIM-NETWORK-1"));

        assertThat(seen).containsExactly(Map.of("T-1", 0), Map.of("T-1", 0));
    }

    @Test
    @DisplayName("without the familiarity assumption a strategy sees no experience at all (so the default comparison is unchanged)")
    void withoutFamiliarityNothingIsShown() {
        List<Map<String, Integer>> seen = new ArrayList<>();

        replay(WorkTimeModel.defaults(), new Probe(ServiceExperienceSource.none(), seen),
                ticket(0, 0, "SIM-NETWORK-1"), ticket(1, SIX_HOURS, "SIM-NETWORK-1"));

        assertThat(seen).containsExactly(Map.of(), Map.of());
    }

    @Test
    @DisplayName("the strategy handed to a replay is re-bound: its own source (the real database) is never read during a simulation")
    void theOriginalSourceIsNeverRead() {
        ServiceExperienceSource realDatabase = (service, ids) -> {
            throw new AssertionError("a simulation must never read the real service experience");
        };
        List<Map<String, Integer>> seen = new ArrayList<>();

        replay(WorkTimeModel.withServiceFamiliarity(0.5, 5), new Probe(realDatabase, seen), ticket(0, 0, "SIM-NETWORK-1"),
                ticket(1, SIX_HOURS, "SIM-NETWORK-1"));

        assertThat(seen).hasSize(2);
    }

    @Test
    @DisplayName("a technician is faster at a service they have resolved before: 1 - speedup x resolved / full, capped; another service gets nothing")
    void familiaritySpeedsUpTheSameService() {
        WorkTimeModel model = WorkTimeModel.withServiceFamiliarity(0.5, 5);

        ReplayResult result = replay(model, new Probe(ServiceExperienceSource.none(), new ArrayList<>()),
                ticket(0, 0, "SIM-NETWORK-1"), ticket(1, SIX_HOURS, "SIM-NETWORK-1"), ticket(2, 2 * SIX_HOURS, "SIM-NETWORK-2"),
                ticket(3, 3 * SIX_HOURS, "SIM-NETWORK-1"));

        long first = result.tickets().get(0).serviceTime().toMillis();
        assertThat(result.tickets().get(1).serviceTime().toMillis() / (double) first).isCloseTo(1.0 - 0.5 * 1 / 5, within(1e-6));
        assertThat(result.tickets().get(2).serviceTime().toMillis()).as("another service: no familiarity").isEqualTo(first);
        assertThat(result.tickets().get(3).serviceTime().toMillis() / (double) first).isCloseTo(1.0 - 0.5 * 2 / 5, within(1e-6));
    }

    @Test
    @DisplayName("without the assumption the same service gives no speed-up")
    void noSpeedUpWithoutTheAssumption() {
        ReplayResult result = replay(WorkTimeModel.defaults(), new Probe(ServiceExperienceSource.none(), new ArrayList<>()),
                ticket(0, 0, "SIM-NETWORK-1"), ticket(1, SIX_HOURS, "SIM-NETWORK-1"));

        assertThat(result.tickets().get(1).serviceTime()).isEqualTo(result.tickets().get(0).serviceTime());
    }

    @Test
    @DisplayName("the familiarity factor: 1 at 0 resolved, 1 - speedup at full, capped above, never below 1 - speedup")
    void familiarityFactor() {
        WorkTimeModel model = WorkTimeModel.withServiceFamiliarity(0.3, 5);

        assertThat(model.familiarityFactor(0)).isEqualTo(1.0);
        assertThat(model.familiarityFactor(2)).isCloseTo(1.0 - 0.3 * 0.4, within(1e-12));
        assertThat(model.familiarityFactor(5)).isCloseTo(0.7, within(1e-12));
        assertThat(model.familiarityFactor(50)).isCloseTo(0.7, within(1e-12));
        assertThat(model.familiarityFactor(-3)).isEqualTo(1.0);
        assertThat(WorkTimeModel.defaults().familiarityFactor(5)).isEqualTo(1.0);
        assertThat(WorkTimeModel.defaults().hasServiceFamiliarity()).isFalse();
        assertThat(model.hasServiceFamiliarity()).isTrue();
        assertThat(model.familiaritySpeedup()).isEqualTo(0.3);
        assertThat(model.familiarityFullTickets()).isEqualTo(5);
    }

    @Test
    @DisplayName("the familiarity assumption is validated and is added to the stated assumptions only when it is on")
    void familiarityAssumption() {
        for (double bad : new double[] {-0.1, 1.0, 2.0, Double.NaN}) {
            assertThatThrownBy(() -> WorkTimeModel.withServiceFamiliarity(bad, 5)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> WorkTimeModel.withServiceFamiliarity(0.3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(String.join(" ", WorkTimeModel.defaults().assumptions())).doesNotContain("Familiarity");
        assertThat(String.join(" ", WorkTimeModel.withServiceFamiliarity(0.3, 5).assumptions()))
                .contains("Familiarity: a technician is up to 30 % faster", "5 or more tickets");
    }

    @Test
    @DisplayName("withServices adds a service per ticket (SIM-<CATEGORY>-k) and changes nothing else in the stream")
    void streamWithServices() {
        TicketStreamGenerator generator = new TicketStreamGenerator(WorkTimeModel.defaults());
        SplittableRandom random = new SplittableRandom(5);
        List<SyntheticTicket> base = generator.generate(random, 300, 15, SimulationPool.syntheticDefault().servableCategories());

        List<SyntheticTicket> withServices = generator.withServices(base, random.split(), 3);

        assertThat(withServices).hasSameSizeAs(base);
        for (int i = 0; i < base.size(); i++) {
            SyntheticTicket a = base.get(i);
            SyntheticTicket b = withServices.get(i);
            assertThat(a.serviceId()).isEqualTo(SyntheticTicket.DEFAULT_SERVICE_ID);
            assertThat(b.serviceId()).matches("SIM-" + a.category().name() + "-[123]");
            assertThat(new SyntheticTicket(b.index(), b.arrivalOffset(), b.priority(), b.category(), b.title(), b.description(),
                    b.noise(), SyntheticTicket.DEFAULT_SERVICE_ID)).isEqualTo(a);
        }
        assertThat(withServices.stream().map(SyntheticTicket::serviceId).distinct().count()).isGreaterThan(10);
        assertThatThrownBy(() -> generator.withServices(base, random, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("prepared incidents carry the ticket's service; a ticket needs a service id")
    void preparedIncidentsCarryTheService() {
        SyntheticTicket t = ticket(0, 0, "SIM-NETWORK-2");

        assertThat(SimulationFixtures.prepared(List.of(t)).get(0).incident().getServiceId()).isEqualTo("SIM-NETWORK-2");
        assertThat(SimulationFixtures.prepared(List.of(SimulationFixtures.ticket(0, 0, Priority.P3, NET))).get(0).incident()
                .getServiceId()).isEqualTo(SyntheticTicket.DEFAULT_SERVICE_ID);
        assertThatThrownBy(() -> ticket(0, 0, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an experiment with services is deterministic, and a composite with the factor is compared like any strategy")
    void comparisonWithServices() {
        List<AssignmentStrategy> strategies = List.of(
                WeightedCompositeStrategy.withServiceExperience(ServiceExperienceSource.none(), 0.10, 5),
                new WeightedCompositeStrategy().named("PLAIN"));
        ComparisonParameters parameters = ComparisonParameters.of(3L, 200, 3, 15.0, null,
                strategies.stream().map(AssignmentStrategy::getStrategyName).toList());
        StrategyComparison experiment = new StrategyComparison(WorkTimeModel.withServiceFamiliarity(0.3, 5), 2);

        ComparisonReport first = experiment.run(parameters, SimulationPool.syntheticDefault(), strategies, SimulationFixtures.targets());
        ComparisonReport again = experiment.run(parameters, SimulationPool.syntheticDefault(), strategies, SimulationFixtures.targets());

        assertThat(again).isEqualTo(first);
        assertThat(first.strategies()).extracting(StrategyReport::strategyName).containsExactly("PLAIN", "WEIGHTED_COMPOSITE");
        assertThat(first.assumptions().toString()).contains("Familiarity");
        assertThatThrownBy(() -> new StrategyComparison(WorkTimeModel.defaults(), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("services do not change the base stream: the same seed gives the same arrivals, priorities and noise with or without them")
    void servicesLeaveTheBaseStreamAlone() {
        List<AssignmentStrategy> strategy = List.of(new WeightedCompositeStrategy());
        ComparisonParameters parameters = ComparisonParameters.of(11L, 150, 2, 20.0, null, List.of("WEIGHTED_COMPOSITE"));

        ComparisonReport plain = new StrategyComparison(WorkTimeModel.defaults()).run(
                parameters, SimulationPool.syntheticDefault(), strategy, SimulationFixtures.targets());
        ComparisonReport withServices = new StrategyComparison(WorkTimeModel.defaults(), 3).run(
                parameters, SimulationPool.syntheticDefault(), strategy, SimulationFixtures.targets());

        assertThat(withServices).as("without familiarity the services change no time and no choice").isEqualTo(plain);
    }
}
