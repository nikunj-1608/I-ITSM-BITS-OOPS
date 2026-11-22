package com.iitsm.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.strategy.assignment.AssignmentGridAccess;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.domain.strategy.assignment.CandidateScore;
import com.iitsm.domain.strategy.assignment.FactorScore;
import com.iitsm.domain.strategy.assignment.ServiceExperienceSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How the application wires the service-experience weight into the default strategy (ADR-044, Phase L4). */
class IntelligenceConfigServiceExperienceTest {

    private final IntelligenceConfig config = new IntelligenceConfig();
    private final Incident ticket = AssignmentGridAccess.ticket();
    private final List<Technician> candidates = AssignmentGridAccess.allTechnicians();

    private static List<String> factorKeys(AssignmentStrategyRegistry registry, Incident ticket, List<Technician> candidates) {
        CandidateScore top = registry.getActiveStrategy().rank(ticket, candidates).get(0);
        return top.factorScores().stream().map(FactorScore::factorKey).toList();
    }

    @Test
    @DisplayName("a positive weight puts the fourth factor into the default strategy; the other four strategies are untouched")
    void positiveWeightAddsTheFactor() {
        AssignmentStrategyRegistry registry = config.assignmentStrategyRegistry(ServiceExperienceSource.none(), 0.10, 5);

        assertThat(registry.getActiveStrategyName()).isEqualTo("WEIGHTED_COMPOSITE");
        assertThat(factorKeys(registry, ticket, candidates)).containsExactly("SKILL_MATCH", "LOAD", "EXPERIENCE", "SERVICE_EXPERIENCE");
        assertThat(registry.getAllStrategies()).hasSize(5);
    }

    @Test
    @DisplayName("a weight of 0 switches the factor off: the historic three factors, and the source is never asked")
    void zeroWeightIsOff() {
        AtomicInteger asked = new AtomicInteger();
        ServiceExperienceSource counting = (service, ids) -> {
            asked.incrementAndGet();
            return java.util.Map.of();
        };

        AssignmentStrategyRegistry registry = config.assignmentStrategyRegistry(counting, 0.0, 5);

        assertThat(factorKeys(registry, ticket, candidates)).containsExactly("SKILL_MATCH", "LOAD", "EXPERIENCE");
        assertThat(asked).hasValue(0);
    }

    @Test
    @DisplayName("the source is asked once per ranking when the factor is on")
    void sourceIsAskedWhenOn() {
        AtomicInteger asked = new AtomicInteger();
        ServiceExperienceSource counting = (service, ids) -> {
            asked.incrementAndGet();
            return java.util.Map.of();
        };

        config.assignmentStrategyRegistry(counting, 0.10, 5).getActiveStrategy().recommend(ticket, candidates);

        assertThat(asked).hasValue(1);
    }

    @Test
    @DisplayName("a weight outside 0 to 0.5 stops the start-up and names the property")
    void badWeightsAreRefused() {
        for (double bad : new double[] {-0.01, 0.51, 1.0, Double.NaN}) {
            assertThatThrownBy(() -> config.assignmentStrategyRegistry(ServiceExperienceSource.none(), bad, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("iitsm.assignment.service-experience-weight");
        }
    }
}
