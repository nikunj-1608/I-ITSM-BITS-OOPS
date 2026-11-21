package com.iitsm.web.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.strategy.assignment.AssignmentDecision;
import com.iitsm.domain.strategy.assignment.AssignmentGridAccess;
import com.iitsm.domain.strategy.assignment.AssignmentRecommendation;
import com.iitsm.domain.strategy.assignment.PoolKind;
import com.iitsm.domain.strategy.assignment.SkillBasedStrategy;
import com.iitsm.domain.strategy.assignment.WeightedCompositeStrategy;
import com.iitsm.web.dto.AdminIncidentDetailView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The "Why this technician" view (ADR-044): names beside ids, the factor numbers, and one sentence per decision kind. */
class AssignmentDecisionViewMapperTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final Map<String, String> NAMES = Map.of("T-NET-A", "Asha Net", "ADMIN-1", "Chief Admin");

    private static AssignmentDecision composite(PoolKind pool) {
        AssignmentRecommendation rec = new WeightedCompositeStrategy().recommend(AssignmentGridAccess.ticket(),
                AssignmentGridAccess.allTechnicians());
        return AssignmentDecision.byStrategy("ASG-1", "INC-1", pool, rec, "SYSTEM", NOW, 10);
    }

    @Test
    @DisplayName("a strategy decision shows every candidate with its factors, and names where they are known")
    void strategyDecisionView() {
        AdminIncidentDetailView.AssignmentDecisionView view =
                AssignmentDecisionViewMapper.toViews(List.of(composite(PoolKind.OWNING_TEAM)), NAMES).get(0);

        assertThat(view.poolKind()).isEqualTo("OWNING_TEAM");
        assertThat(view.poolLabel()).isEqualTo("Owning team");
        assertThat(view.chosenTechnicianId()).isEqualTo("T-NET-A");
        assertThat(view.chosenTechnicianName()).isEqualTo("Asha Net");
        assertThat(view.actorName()).isNull();
        assertThat(view.candidates()).hasSize(8);
        AdminIncidentDetailView.CandidateView top = view.candidates().get(0);
        assertThat(top.chosen()).isTrue();
        assertThat(top.technicianName()).isEqualTo("Asha Net");
        assertThat(view.candidates().get(1).technicianName()).isNull();
        assertThat(top.factors()).extracting(AdminIncidentDetailView.FactorView::key)
                .containsExactly("SKILL_MATCH", "LOAD", "EXPERIENCE");
        assertThat(view.summary()).isEqualTo(
                "WEIGHTED_COMPOSITE chose Asha Net (T-NET-A) from 8 available technician(s) of the owning team.");
    }

    @Test
    @DisplayName("the skill-based penalty keeps its negative weight in the view")
    void penaltyKeepsItsSign() {
        AssignmentRecommendation rec = new SkillBasedStrategy().recommend(AssignmentGridAccess.ticket(),
                AssignmentGridAccess.allTechnicians());
        AssignmentDecision decision = AssignmentDecision.byStrategy("ASG-1", "INC-1", PoolKind.OWNING_TEAM, rec,
                "SYSTEM", NOW, 10);
        AdminIncidentDetailView.FactorView penalty = AssignmentDecisionViewMapper.toViews(List.of(decision), NAMES)
                .get(0).candidates().get(0).factors().get(1);

        assertThat(penalty.key()).isEqualTo("LOAD_PENALTY");
        assertThat(penalty.weight()).isNegative();
    }

    @Test
    @DisplayName("each decision kind has its own plain sentence; unknown names fall back to the id")
    void sentences() {
        assertThat(AssignmentDecisionViewMapper.summary(composite(PoolKind.SKILLED_OVERFLOW), NAMES))
                .startsWith("The owning team had nobody free, so WEIGHTED_COMPOSITE chose Asha Net (T-NET-A)");
        assertThat(AssignmentDecisionViewMapper.summary(
                AssignmentDecision.manual("A", "INC-1", "T-NET-A", "ADMIN-1", NOW), NAMES))
                .isEqualTo("Chief Admin (ADMIN-1) assigned Asha Net (T-NET-A) by hand; no strategy was used.");
        assertThat(AssignmentDecisionViewMapper.summary(
                AssignmentDecision.inheritedFromParent("A", "INC-2", "TECH-X", "INC-1", "SYSTEM", NOW), NAMES))
                .isEqualTo("This ticket is a duplicate of INC-1, so it rides that ticket's technician TECH-X without "
                        + "taking a capacity slot.");
        assertThat(AssignmentDecisionViewMapper.summary(
                AssignmentDecision.requeuedToSameTechnician("A", "INC-1", "T-NET-A", "ADMIN-1", NOW), NAMES))
                .isEqualTo("Chief Admin (ADMIN-1) rejected the wrong-domain request, so the ticket went back to "
                        + "Asha Net (T-NET-A).");
    }

    @Test
    @DisplayName("a detail without decisions maps to an empty list (tickets assigned before ADR-044)")
    void noDecisions() {
        assertThat(AssignmentDecisionViewMapper.toViews(List.of(), NAMES)).isEmpty();
    }
}
