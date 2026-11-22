package com.iitsm.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.strategy.assignment.CandidateScore;
import com.iitsm.domain.strategy.assignment.FactorScore;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The JSON form of an assignment ranking (ADR-044): exact round trip, a fixed format number, and no surprises from a
 * document written by another version.
 */
class CandidateScoresJsonTest {

    private final CandidateScoresJson json = new CandidateScoresJson();

    private static List<CandidateScore> ranking() {
        return List.of(
                new CandidateScore("TECH-1", List.of(
                        FactorScore.of("SKILL_MATCH", 0.8000000000000002, 0.45, "4 of 5 skill points"),
                        FactorScore.of("LOAD", 1.0 - 1 / 3.0, 0.20, "1/3 tickets active \"quoted\" <b>"),
                        FactorScore.of("EXPERIENCE", 10 / 12.0, 0.35, "10 years")), 0.8291666666666668, 1, true),
                new CandidateScore("TECH-2", List.of(
                        FactorScore.of("LOAD_PENALTY", 0.5, -0.10, "")), 0.0, 2, false));
    }

    @Test
    @DisplayName("a ranking reads back exactly as it was written, every double to the last bit")
    void roundTripIsExact() {
        List<CandidateScore> original = ranking();
        assertThat(json.read(json.write(original))).isEqualTo(original);
        assertThat(json.read(json.write(List.of()))).isEmpty();
    }

    @Test
    @DisplayName("the document has a format number and the documented field names")
    void documentShape() {
        String written = json.write(ranking());
        assertThat(written).startsWith("{\"format\":1,\"candidates\":[");
        assertThat(written).contains("\"technicianId\":\"TECH-1\"", "\"key\":\"SKILL_MATCH\"", "\"raw\":",
                "\"weight\":", "\"contribution\":", "\"detail\":", "\"rank\":2", "\"chosen\":false");
        assertThat(json.write(List.of())).isEqualTo("{\"format\":1,\"candidates\":[]}");
    }

    @Test
    @DisplayName("an unknown format or a broken document is refused; unknown fields are ignored")
    void refusesWhatItCannotRead() {
        assertThatThrownBy(() -> json.read("{\"format\":2,\"candidates\":[]}"))
                .isInstanceOf(PersistenceFailureException.class);
        assertThatThrownBy(() -> json.read("not json")).isInstanceOf(PersistenceFailureException.class);
        assertThat(json.read("{\"format\":1,\"extra\":true,\"candidates\":[]}")).isEmpty();
    }
}
