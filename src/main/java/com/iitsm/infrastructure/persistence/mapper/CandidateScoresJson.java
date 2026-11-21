package com.iitsm.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.strategy.assignment.CandidateScore;
import com.iitsm.domain.strategy.assignment.FactorScore;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pattern: Mapper. Writes and reads the ranking of an assignment decision as one JSON document in
 * {@code assignment_decisions.candidates_json} (ADR-044). The domain never sees JSON: this class turns the domain's
 * {@link CandidateScore} list into private records that Jackson serialises, and back.
 *
 * <p>Document: {@code {"format":1,"candidates":[{"technicianId":..,"total":..,"rank":..,"chosen":..,
 * "factors":[{"key":..,"raw":..,"weight":..,"contribution":..,"detail":..}]}]}}. Numbers are written in Java's
 * shortest exact form, so a score reads back to the same double. An unknown format number is refused, so a future
 * format change cannot be misread silently; unknown fields are ignored.
 */
@Component
public class CandidateScoresJson {

    /** The document format this class writes. */
    public static final int FORMAT = 1;

    private final ObjectMapper mapper;

    public CandidateScoresJson() {
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** The ranking as a JSON document. */
    public String write(List<CandidateScore> candidates) {
        List<StoredCandidate> stored = candidates.stream().map(CandidateScoresJson::toStored).toList();
        try {
            return mapper.writeValueAsString(new Document(FORMAT, stored));
        } catch (JsonProcessingException ex) {
            throw new PersistenceFailureException("Failed to write an assignment ranking", ex);
        }
    }

    /** The ranking stored in {@code json}. */
    public List<CandidateScore> read(String json) {
        try {
            Document document = mapper.readValue(json, Document.class);
            if (document.format() != FORMAT) {
                throw new PersistenceFailureException("Unknown assignment ranking format " + document.format());
            }
            List<StoredCandidate> stored = document.candidates() == null ? List.of() : document.candidates();
            return stored.stream().map(CandidateScoresJson::toDomain).toList();
        } catch (JsonProcessingException ex) {
            throw new PersistenceFailureException("Failed to read an assignment ranking", ex);
        }
    }

    private static StoredCandidate toStored(CandidateScore c) {
        List<StoredFactor> factors = c.factorScores().stream()
                .map(f -> new StoredFactor(f.factorKey(), f.rawScore(), f.weight(), f.contribution(), f.detail()))
                .toList();
        return new StoredCandidate(c.technicianId(), c.total(), c.rank(), c.chosen(), factors);
    }

    private static CandidateScore toDomain(StoredCandidate c) {
        List<StoredFactor> stored = c.factors() == null ? List.of() : c.factors();
        List<FactorScore> factors = stored.stream()
                .map(f -> new FactorScore(f.key(), f.raw(), f.weight(), f.contribution(), f.detail()))
                .toList();
        return new CandidateScore(c.technicianId(), factors, c.total(), c.rank(), c.chosen());
    }

    record Document(int format, List<StoredCandidate> candidates) {
    }

    record StoredCandidate(String technicianId, double total, int rank, boolean chosen, List<StoredFactor> factors) {
    }

    record StoredFactor(String key, double raw, double weight, double contribution, String detail) {
    }
}
