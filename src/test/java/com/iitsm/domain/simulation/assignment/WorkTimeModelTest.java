package com.iitsm.domain.simulation.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Skill;
import java.time.Duration;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The work-time model of the offline comparison (ADR-044, Phase L3): every assumption is a number tested here. */
class WorkTimeModelTest {

    private final WorkTimeModel model = WorkTimeModel.defaults();

    private static TechnicianProfile tech(int years, int capacity, IncidentCategory skill) {
        return new TechnicianProfile("T-1", "TEAM-T", years, capacity, Set.of(new Skill("Skill", skill)));
    }

    private static SyntheticTicket ticket(Priority priority, IncidentCategory category, double noise) {
        return new SyntheticTicket(0, Duration.ZERO, priority, category, "Replay ticket",
                "A ticket for the work time model tests.", noise);
    }

    private double minutes(SyntheticTicket ticket, TechnicianProfile tech, int inService) {
        return model.serviceTime(ticket, tech, inService).toMillis() / 60_000.0;
    }

    @Test
    @DisplayName("base work, category factor and a 12-year technician: P3 network = 150 x 1.1 x 0.8 minutes")
    void baseTimes() {
        // 12 years or more is the floor of the experience factor (0.80)
        assertThat(minutes(ticket(Priority.P3, IncidentCategory.NETWORK, 1.0), tech(12, 5, IncidentCategory.NETWORK), 0))
                .isCloseTo(150 * 1.1 * 0.80, within(1e-6));
        assertThat(minutes(ticket(Priority.P1, IncidentCategory.HARDWARE, 1.0), tech(20, 5, IncidentCategory.HARDWARE), 0))
                .isCloseTo(40 * 1.0 * 0.80, within(1e-6));
        assertThat(minutes(ticket(Priority.P4, IncidentCategory.SECURITY, 1.0), tech(12, 5, IncidentCategory.SECURITY), 0))
                .isCloseTo(240 * 1.3 * 0.80, within(1e-6));
    }

    @Test
    @DisplayName("a technician without the category skill needs 1.6 times as long")
    void unskilledIsSlower() {
        SyntheticTicket ticket = ticket(Priority.P2, IncidentCategory.NETWORK, 1.0);
        double skilled = minutes(ticket, tech(6, 4, IncidentCategory.NETWORK), 0);
        double unskilled = minutes(ticket, tech(6, 4, IncidentCategory.DATABASE), 0);

        assertThat(unskilled / skilled).isCloseTo(WorkTimeModel.UNSKILLED_SLOWDOWN, within(1e-6));
    }

    @Test
    @DisplayName("experience: 1.25 at 0 years, linear down to 0.80 at 12 years, flat after")
    void experienceFactor() {
        assertThat(WorkTimeModel.experienceFactor(0)).isEqualTo(1.25);
        assertThat(WorkTimeModel.experienceFactor(6)).isCloseTo(1.025, within(1e-12));
        assertThat(WorkTimeModel.experienceFactor(12)).isCloseTo(0.80, within(1e-12));
        assertThat(WorkTimeModel.experienceFactor(40)).isCloseTo(0.80, within(1e-12));
        SyntheticTicket ticket = ticket(Priority.P3, IncidentCategory.NETWORK, 1.0);
        assertThat(minutes(ticket, tech(1, 4, IncidentCategory.NETWORK), 0))
                .isGreaterThan(minutes(ticket, tech(10, 4, IncidentCategory.NETWORK), 0));
    }

    @Test
    @DisplayName("concurrency: 1 + 0.30 x tickets already in service / capacity; a penalty of 0 is independent work")
    void concurrencyPenalty() {
        SyntheticTicket ticket = ticket(Priority.P3, IncidentCategory.NETWORK, 1.0);
        TechnicianProfile tech = tech(5, 4, IncidentCategory.NETWORK);
        double alone = minutes(ticket, tech, 0);

        assertThat(minutes(ticket, tech, 2) / alone).isCloseTo(1.0 + 0.30 * 2 / 4, within(1e-6));
        assertThat(minutes(ticket, tech, 3) / alone).isCloseTo(1.0 + 0.30 * 3 / 4, within(1e-6));
        WorkTimeModel independent = WorkTimeModel.withConcurrencyPenalty(0.0);
        assertThat(independent.serviceTime(ticket, tech, 3)).isEqualTo(independent.serviceTime(ticket, tech, 0));
        assertThatThrownBy(() -> WorkTimeModel.withConcurrencyPenalty(-0.1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a ticket can only start on a free slot")
    void needsAFreeSlot() {
        SyntheticTicket ticket = ticket(Priority.P3, IncidentCategory.NETWORK, 1.0);
        TechnicianProfile tech = tech(5, 2, IncidentCategory.NETWORK);

        assertThatThrownBy(() -> model.serviceTime(ticket, tech, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model.serviceTime(ticket, tech, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the ticket noise multiplies the time, and the drawn noise has mean 1 and is always positive")
    void noise() {
        TechnicianProfile tech = tech(5, 4, IncidentCategory.NETWORK);
        assertThat(minutes(ticket(Priority.P3, IncidentCategory.NETWORK, 2.0), tech, 0))
                .isCloseTo(2 * minutes(ticket(Priority.P3, IncidentCategory.NETWORK, 1.0), tech, 0), within(1e-6));

        SplittableRandom random = new SplittableRandom(11);
        double sum = 0;
        int n = 50_000;
        for (int i = 0; i < n; i++) {
            double noise = model.drawNoise(random);
            assertThat(noise).isPositive();
            sum += noise;
        }
        assertThat(sum / n).isCloseTo(1.0, within(0.02));
    }

    @Test
    @DisplayName("the assumptions are stated in words and name the numbers used")
    void assumptionsAreStated() {
        assertThat(model.assumptions()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(String.join(" ", model.assumptions())).contains("P3 150", "1.6", "30 %", "several tickets at once");
        assertThat(WorkTimeModel.withConcurrencyPenalty(0.5).assumptions().toString()).contains("50 %");
    }
}
