package com.iitsm.domain.strategy.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Technician;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Service experience, its port and the factor that scores it (ADR-044, Phase L4). */
class ServiceExperienceTest {

    private final Map<String, Technician> techs = AssignmentGrid.technicians();

    private AssignmentContext contextWith(String serviceId, Map<String, Integer> counts) {
        return AssignmentContext.of(AssignmentGrid.incidents().get("NET_P3"))
                .withServiceExperience(new ServiceExperience(serviceId, counts));
    }

    @Test
    @DisplayName("experience applies to a specific IT service only: not to no service, a blank one or the general service")
    void appliesToSpecificServicesOnly() {
        assertThat(ServiceExperience.none().applies()).isFalse();
        assertThat(new ServiceExperience(null, Map.of()).applies()).isFalse();
        assertThat(new ServiceExperience(" ", Map.of()).applies()).isFalse();
        assertThat(new ServiceExperience("SRV-GEN-001", Map.of()).applies()).isFalse();
        assertThat(new ServiceExperience("SVC-VPN", Map.of()).applies()).isTrue();
    }

    @Test
    @DisplayName("counts are copied, unknown technicians have 0, and a negative count is refused")
    void countsAreCopiedAndChecked() {
        Map<String, Integer> source = new HashMap<>(Map.of("T-1", 3));
        ServiceExperience experience = new ServiceExperience("SVC-VPN", source);
        source.put("T-2", 9);

        assertThat(experience.resolvedBy("T-1")).isEqualTo(3);
        assertThat(experience.resolvedBy("T-2")).isZero();
        assertThat(experience.resolvedBy("nobody")).isZero();
        assertThatThrownBy(() -> experience.resolvedByTechnician().put("T-3", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ServiceExperience("SVC-VPN", Map.of("T-1", -1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the none source knows nothing; the port is a function, so any lookup can stand in for it")
    void noneSourceAndFunctionalPort() {
        assertThat(ServiceExperienceSource.none().resolvedTicketCounts("SVC-VPN", java.util.List.of("T-1"))).isEmpty();
        ServiceExperienceSource fixed = (service, ids) -> Map.of("T-1", 4);
        assertThat(fixed.resolvedTicketCounts("SVC-VPN", java.util.List.of("T-1"))).containsEntry("T-1", 4);
    }

    @Test
    @DisplayName("the factor scores resolved tickets / 5, capped at 1, and says how many in words")
    void factorScore() {
        ServiceExperienceFactor factor = new ServiceExperienceFactor(5);
        AssignmentContext context = contextWith("SVC-VPN", Map.of("T-APP", 2, "T-OLD", 5, "T-VET", 40));

        AssignmentFactor.Reading some = factor.read(context, techs.get("T-APP"));
        assertThat(some.rawScore()).isEqualTo(0.4);
        assertThat(some.detail()).isEqualTo("2 ticket(s) of this service resolved (full score at 5)");
        assertThat(factor.read(context, techs.get("T-OLD")).rawScore()).isEqualTo(1.0);
        assertThat(factor.read(context, techs.get("T-VET")).rawScore()).as("capped").isEqualTo(1.0);
        assertThat(factor.read(context, techs.get("T-NONE")).rawScore()).as("no history").isZero();
    }

    @Test
    @DisplayName("a ticket without a specific service scores every candidate 0, whatever the counts say")
    void noSpecificService() {
        ServiceExperienceFactor factor = new ServiceExperienceFactor(5);

        AssignmentFactor.Reading general = factor.read(contextWith("SRV-GEN-001", Map.of("T-APP", 9)), techs.get("T-APP"));
        AssignmentFactor.Reading none = factor.read(AssignmentContext.of(AssignmentGrid.incidents().get("RAW_VAGUE")), techs.get("T-APP"));

        assertThat(general.rawScore()).isZero();
        assertThat(none.rawScore()).isZero();
        assertThat(general.detail()).isEqualTo("no specific IT service on this ticket");
    }

    @Test
    @DisplayName("key, label and the full-score rule")
    void keyLabelAndFullScore() {
        ServiceExperienceFactor factor = new ServiceExperienceFactor(ServiceExperienceFactor.DEFAULT_FULL_SCORE_TICKETS);

        assertThat(factor.key()).isEqualTo("SERVICE_EXPERIENCE");
        assertThat(factor.label()).isEqualTo("Service");
        assertThat(ServiceExperienceFactor.DEFAULT_FULL_SCORE_TICKETS).isEqualTo(5);
        assertThatThrownBy(() -> new ServiceExperienceFactor(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ServiceExperienceFactor(10).read(contextWith("SVC-VPN", Map.of("T-APP", 5)), techs.get("T-APP")).rawScore())
                .isEqualTo(0.5);
    }

    @Test
    @DisplayName("withFactor scales the historic weights by 1 - w so every priority still sums to 1 and the proportions stay")
    void weightTableWithTheNewFactor() {
        FactorWeights base = FactorWeights.defaults();
        FactorWeights withService = base.withFactor(ServiceExperienceFactor.KEY, 0.10);

        for (Priority priority : Priority.values()) {
            double sum = 0.0;
            for (String key : new String[] {"SKILL_MATCH", "LOAD", "EXPERIENCE", ServiceExperienceFactor.KEY}) {
                sum += withService.weightOf(priority, key);
            }
            assertThat(sum).as("%s", priority).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(withService.weightOf(priority, ServiceExperienceFactor.KEY)).isEqualTo(0.10);
            assertThat(withService.weightOf(priority, "SKILL_MATCH") / withService.weightOf(priority, "LOAD"))
                    .isCloseTo(base.weightOf(priority, "SKILL_MATCH") / base.weightOf(priority, "LOAD"), org.assertj.core.data.Offset.offset(1e-9));
        }
        assertThat(withService.weightOf(Priority.P3, "SKILL_MATCH")).isCloseTo(0.45, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(withService.weightOf(Priority.P3, "LOAD")).isCloseTo(0.27, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(withService.weightOf(Priority.P3, "EXPERIENCE")).isCloseTo(0.18, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(withService.covers(ServiceExperienceFactor.KEY)).isTrue();
        assertThat(base.covers(ServiceExperienceFactor.KEY)).as("the original table is unchanged").isFalse();
    }

    @Test
    @DisplayName("a weight of 0 returns the same table; a bad weight or a factor that is already there is refused")
    void weightTableRules() {
        FactorWeights base = FactorWeights.defaults();

        assertThat(base.withFactor(ServiceExperienceFactor.KEY, 0.0)).isSameAs(base);
        for (double bad : new double[] {-0.1, 1.0, 1.5, Double.NaN}) {
            assertThatThrownBy(() -> base.withFactor(ServiceExperienceFactor.KEY, bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> base.withFactor("LOAD", 0.1)).isInstanceOf(IllegalArgumentException.class);
    }
}
