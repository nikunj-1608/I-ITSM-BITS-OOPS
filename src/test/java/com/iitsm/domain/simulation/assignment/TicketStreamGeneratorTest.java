package com.iitsm.domain.simulation.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The seeded ticket stream of one replication (ADR-044, Phase L3). */
class TicketStreamGeneratorTest {

    private final TicketStreamGenerator generator = new TicketStreamGenerator(WorkTimeModel.defaults());
    private final Set<IncidentCategory> all = EnumSet.allOf(IncidentCategory.class);

    private List<SyntheticTicket> stream(long seed, int count, double rate, Set<IncidentCategory> servable) {
        return generator.generate(new SplittableRandom(seed), count, rate, servable);
    }

    @Test
    @DisplayName("the same seed gives the same stream, another seed a different one")
    void determinism() {
        assertThat(stream(42, 300, 15, all)).isEqualTo(stream(42, 300, 15, all));
        assertThat(stream(42, 300, 15, all)).isNotEqualTo(stream(43, 300, 15, all));
    }

    @Test
    @DisplayName("arrivals never go back in time, and the stream has exactly the requested size and indexes")
    void orderAndSize() {
        List<SyntheticTicket> tickets = stream(5, 400, 15, all);

        assertThat(tickets).hasSize(400);
        for (int i = 0; i < tickets.size(); i++) {
            assertThat(tickets.get(i).index()).isEqualTo(i);
            if (i > 0) {
                assertThat(tickets.get(i).arrivalOffset()).isGreaterThanOrEqualTo(tickets.get(i - 1).arrivalOffset());
            }
        }
    }

    @Test
    @DisplayName("the mean gap between arrivals is 60 / rate minutes (exponential gaps)")
    void arrivalRate() {
        List<SyntheticTicket> tickets = stream(9, 20_000, 30, all);
        double meanGapMinutes = tickets.get(tickets.size() - 1).arrivalOffset().toMillis() / 60_000.0 / tickets.size();

        assertThat(meanGapMinutes).isCloseTo(2.0, within(0.06));
    }

    @Test
    @DisplayName("the priority mix is 5 / 15 / 50 / 30 % and the category mix follows its weights")
    void mixes() {
        List<SyntheticTicket> tickets = stream(3, 40_000, 15, all);
        Map<Priority, Integer> priorities = new EnumMap<>(Priority.class);
        Map<IncidentCategory, Integer> categories = new EnumMap<>(IncidentCategory.class);
        tickets.forEach(t -> {
            priorities.merge(t.priority(), 1, Integer::sum);
            categories.merge(t.category(), 1, Integer::sum);
        });

        TicketStreamGenerator.PRIORITY_MIX.forEach((priority, share) ->
                assertThat(priorities.get(priority) / 40_000.0).as("%s", priority).isCloseTo(share, within(0.012)));
        TicketStreamGenerator.CATEGORY_MIX.forEach((category, share) ->
                assertThat(categories.get(category) / 40_000.0).as("%s", category).isCloseTo(share, within(0.012)));
        assertThat(TicketStreamGenerator.PRIORITY_MIX.values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(1e-12));
        assertThat(TicketStreamGenerator.CATEGORY_MIX.values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    @DisplayName("only servable categories are drawn, so every ticket can eventually be served")
    void onlyServableCategories() {
        Set<IncidentCategory> servable = EnumSet.of(IncidentCategory.NETWORK, IncidentCategory.STORAGE);

        assertThat(stream(1, 2_000, 15, servable)).extracting(SyntheticTicket::category)
                .containsOnly(IncidentCategory.NETWORK, IncidentCategory.STORAGE)
                .contains(IncidentCategory.NETWORK, IncidentCategory.STORAGE);
    }

    @Test
    @DisplayName("the noise is positive, drawn once per ticket and has mean 1")
    void noise() {
        List<SyntheticTicket> tickets = stream(2, 30_000, 15, all);

        assertThat(tickets).allSatisfy(t -> assertThat(t.noise()).isPositive());
        assertThat(tickets.stream().mapToDouble(SyntheticTicket::noise).average().orElseThrow()).isCloseTo(1.0, within(0.02));
    }

    @Test
    @DisplayName("prepared tickets are classified in-memory incidents SIM-000001..., created at the start plus the offset")
    void preparedIncidents() {
        List<SyntheticTicket> tickets = stream(8, 50, 15, all);
        List<PreparedTicket> prepared = generator.prepare(tickets, SimulationFixtures.START);

        assertThat(prepared).hasSize(50);
        for (int i = 0; i < prepared.size(); i++) {
            Incident incident = prepared.get(i).incident();
            SyntheticTicket spec = prepared.get(i).spec();
            assertThat(incident.getId()).isEqualTo(String.format("SIM-%06d", i + 1));
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
            assertThat(incident.getAssigneeId()).isNull();
            assertThat(incident.getCategory()).isEqualTo(spec.category());
            assertThat(incident.getPriorityDecision().priority()).isEqualTo(spec.priority());
            assertThat(incident.getCreatedAt()).isEqualTo(SimulationFixtures.START.plus(spec.arrivalOffset()));
            assertThat(incident.getTitle()).isEqualTo(spec.title());
        }
    }

    @Test
    @DisplayName("every template yields a valid incident (title and description rules of the domain)")
    void everyTemplateIsAValidIncident() {
        List<SyntheticTicket> tickets = stream(4, 5_000, 15, all);

        assertThat(tickets.stream().map(SyntheticTicket::title).distinct().count()).isEqualTo(27L);
        assertThat(generator.prepare(tickets, SimulationFixtures.START)).hasSize(5_000);
    }

    @Test
    @DisplayName("bad arguments are refused")
    void badArguments() {
        assertThatThrownBy(() -> stream(1, 0, 15, all)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stream(1, 10, 0, all)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stream(1, 10, Double.NaN, all)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stream(1, 10, 15, EnumSet.noneOf(IncidentCategory.class))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Duration.ZERO).isNotNull();
    }

    @Test
    @DisplayName("the stream assumptions name the priority mix")
    void assumptionsNameTheMix() {
        assertThat(TicketStreamGenerator.assumptions().get(0)).contains("P1 5 %", "P2 15 %", "P3 50 %", "P4 30 %");
    }
}
