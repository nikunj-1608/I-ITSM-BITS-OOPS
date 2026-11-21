package com.iitsm.infrastructure.persistence.repository;

import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;
import com.iitsm.infrastructure.persistence.entity.SuggestionVoteJpaEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code suggestion_votes} (ADR-041). */
public interface SpringDataSuggestionVoteRepository extends JpaRepository<SuggestionVoteJpaEntity, Long> {

    Optional<SuggestionVoteJpaEntity> findByVoterIdAndSourceTypeAndSourceIdAndTargetIncidentId(
            String voterId, SuggestionSourceType sourceType, String sourceId, String targetIncidentId);

    /** Worked and did-not-work counts per source; the caller keeps only the source types it asked for. */
    @Query("SELECT new com.iitsm.infrastructure.persistence.repository.VoteCountRow(v.sourceType, v.sourceId, "
            + "SUM(CASE WHEN v.worked = true THEN 1 ELSE 0 END), SUM(CASE WHEN v.worked = false THEN 1 ELSE 0 END)) "
            + "FROM SuggestionVoteJpaEntity v WHERE v.sourceId IN :sourceIds GROUP BY v.sourceType, v.sourceId")
    List<VoteCountRow> countBySourceIds(@Param("sourceIds") Collection<String> sourceIds);
}
