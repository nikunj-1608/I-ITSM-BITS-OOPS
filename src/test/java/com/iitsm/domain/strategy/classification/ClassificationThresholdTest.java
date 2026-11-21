package com.iitsm.domain.strategy.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.IncidentCategory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Remaining-defects plan, Phase F (decision D13): the confidence model is kept and its boundaries are pinned. A ticket
 * below 0.60 goes to triage; 0.55 is the cap the composite classifier applies; 0.60 and 0.61 are confident.
 */
class ClassificationThresholdTest {

    private static ClassificationResult at(double confidence) {
        return ClassificationResult.of(IncidentCategory.NETWORK, confidence, "TEST", List.of());
    }

    @ParameterizedTest(name = "confidence {0} -> triage {1}")
    @CsvSource({"0.20,true", "0.55,true", "0.59,true", "0.5999,true", "0.60,false", "0.61,false", "0.90,false"})
    @DisplayName("D13: the gate is 0.60, a ticket below it requires triage")
    void gateIsPinned(double confidence, boolean triage) {
        assertThat(at(confidence).requiresTriage()).isEqualTo(triage);
    }

    @Test
    @DisplayName("D13: the threshold constant is 0.60 and the triage cap sits below it")
    void constantsArePinned() {
        assertThat(ClassificationResult.TRIAGE_THRESHOLD).isEqualTo(0.60);
        assertThat(at(0.55).requiresTriage()).isTrue();
    }

    @Test
    @DisplayName("Confidence is clamped to 0..1")
    void confidenceIsClamped() {
        assertThat(at(1.7).confidence()).isEqualTo(1.0);
        assertThat(at(-0.2).confidence()).isEqualTo(0.0);
    }
}
