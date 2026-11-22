package com.iitsm.domain.strategy.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three extracted assignment factors and the skill evidence they share (ADR-044).
 */
class AssignmentFactorTest {

    private final Map<String, Technician> techs = AssignmentGrid.technicians();
    private final Map<String, Incident> tickets = AssignmentGrid.incidents();

    @Test
    @DisplayName("skill evidence counts category skills, full skill names (2) and contained tokens (1)")
    void skillEvidenceCountsCategoryNameAndTokens() {
        AssignmentContext network = AssignmentContext.of(tickets.get("NET_P3"));
        // "bgp" appears in full (2); "cisco routing" is not in the text, but the tokens "cisco" and "rout" are (1 + 1)
        assertThat(SkillEvidence.of(techs.get("T-NET-A"), network)).isEqualTo(new SkillEvidence(2, 4));
        assertThat(SkillEvidence.of(techs.get("T-NONE"), network)).isEqualTo(new SkillEvidence(0, 0));

        AssignmentContext vpn = AssignmentContext.of(tickets.get("VPN_P2"));
        // "vpn gateway" is not in the text as a phrase: tokens gateway, vpn, vpn each count once
        assertThat(SkillEvidence.of(techs.get("T-VET"), vpn)).isEqualTo(new SkillEvidence(1, 3));
    }

    @Test
    @DisplayName("skill evidence without a category counts keywords only")
    void unclassifiedTicketCountsKeywordsOnly() {
        AssignmentContext raw = AssignmentContext.of(tickets.get("RAW_JAVA"));
        assertThat(raw.category()).isNull();
        assertThat(SkillEvidence.of(techs.get("T-APP"), raw)).isEqualTo(new SkillEvidence(0, 4));
    }

    @Test
    @DisplayName("skill match: 2 points per category skill plus keyword points, 0.20 each, capped at 1")
    void skillMatchFactor() {
        SkillMatchFactor factor = new SkillMatchFactor();
        AssignmentFactor.Reading full = factor.read(AssignmentContext.of(tickets.get("NET_P3")), techs.get("T-NET-A"));
        assertThat(full.rawScore()).isEqualTo(1.0);
        assertThat(full.detail()).isEqualTo("5 of 5 skill points (2 category skills, 4 keyword points)");

        AssignmentFactor.Reading partial = factor.read(AssignmentContext.of(tickets.get("RAW_JAVA")), techs.get("T-APP"));
        assertThat(partial.rawScore()).isEqualTo(4 * 0.20);
        assertThat(partial.detail()).isEqualTo("4 of 5 skill points (0 category skills, 4 keyword points)");
    }

    @Test
    @DisplayName("load: 1 minus the load ratio, with active and maximum tickets in the detail")
    void loadFactor() {
        AssignmentFactor.Reading reading = new LoadFactor()
                .read(AssignmentContext.of(tickets.get("NET_P3")), techs.get("T-JUN"));
        assertThat(reading.rawScore()).isEqualTo(1.0 - 4 / 5.0);
        assertThat(reading.detail()).isEqualTo("4/5 tickets active");
    }

    @Test
    @DisplayName("experience: years over the full-score years, capped at 1")
    void experienceFactor() {
        AssignmentContext context = AssignmentContext.of(tickets.get("NET_P3"));
        ExperienceFactor composite = new ExperienceFactor(ExperienceFactor.COMPOSITE_FULL_SCORE_YEARS);
        assertThat(composite.read(context, techs.get("T-APP")).rawScore()).isEqualTo(10 / 12.0);
        assertThat(composite.read(context, techs.get("T-VET")).rawScore()).isEqualTo(1.0);
        assertThat(composite.read(context, techs.get("T-APP")).detail()).isEqualTo("10 years (full score at 12)");
        ExperienceFactor strategy = new ExperienceFactor(ExperienceFactor.EXPERIENCE_STRATEGY_FULL_SCORE_YEARS);
        assertThat(strategy.read(context, techs.get("T-VET")).rawScore()).isEqualTo(1.0);
        assertThat(strategy.read(context, techs.get("T-NONE")).rawScore()).isEqualTo(0.0);
        assertThatThrownBy(() -> new ExperienceFactor(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("each factor has a stable key and a label")
    void keysAndLabels() {
        assertThat(new SkillMatchFactor().key()).isEqualTo("SKILL_MATCH");
        assertThat(new LoadFactor().key()).isEqualTo("LOAD");
        assertThat(new ExperienceFactor(12).key()).isEqualTo("EXPERIENCE");
        assertThat(new SkillMatchFactor().label()).isEqualTo("Skills");
    }
}
