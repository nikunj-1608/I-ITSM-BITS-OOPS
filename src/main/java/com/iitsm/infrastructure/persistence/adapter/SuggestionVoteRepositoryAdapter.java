package com.iitsm.infrastructure.persistence.adapter;

import com.iitsm.domain.exception.ConcurrentUpdateException;
import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.repository.SuggestionVoteRepository;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceKey;
import com.iitsm.domain.strategy.suggestion.SuggestionVote;
import com.iitsm.domain.strategy.suggestion.VoteTallies;
import com.iitsm.domain.strategy.suggestion.VoteTally;
import com.iitsm.infrastructure.persistence.entity.SuggestionVoteJpaEntity;
import com.iitsm.infrastructure.persistence.repository.SpringDataSuggestionVoteRepository;
import com.iitsm.infrastructure.persistence.repository.VoteCountRow;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Pattern: Adapter / Repository. Stores suggestion votes (ADR-041). A vote is an upsert on the unique key (voter, source,
 * target ticket); a lost race on the first vote of a key is a {@link ConcurrentUpdateException} (HTTP 409).
 */
@Component
public class SuggestionVoteRepositoryAdapter implements SuggestionVoteRepository {

    private final SpringDataSuggestionVoteRepository springDataRepository;

    public SuggestionVoteRepositoryAdapter(SpringDataSuggestionVoteRepository springDataRepository) {
        this.springDataRepository = Objects.requireNonNull(springDataRepository, "springDataRepository cannot be null");
    }

    @Override
    public Optional<SuggestionVote> findVote(String voterId, SuggestionSourceKey source, String targetIncidentId) {
        try {
            return findEntity(voterId, source, targetIncidentId).map(SuggestionVoteRepositoryAdapter::toDomain);
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to read a suggestion vote", ex);
        }
    }

    @Override
    public SuggestionVote save(SuggestionVote vote) {
        Objects.requireNonNull(vote, "vote cannot be null");
        try {
            SuggestionVoteJpaEntity entity = findEntity(vote.voterId(), vote.source(), vote.targetIncidentId())
                    .orElseGet(SuggestionVoteJpaEntity::new);
            entity.setVoterId(vote.voterId());
            entity.setSourceType(vote.source().type());
            entity.setSourceId(vote.source().id());
            entity.setTargetIncidentId(vote.targetIncidentId());
            entity.setWorked(vote.worked());
            entity.setVotedAt(vote.votedAt());
            return toDomain(springDataRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException ex) {
            throw new ConcurrentUpdateException(ex);
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to save a suggestion vote", ex);
        }
    }

    @Override
    public VoteTallies tallies(Collection<SuggestionSourceKey> sources) {
        if (sources == null || sources.isEmpty()) {
            return VoteTallies.none();
        }
        Set<SuggestionSourceKey> wanted = Set.copyOf(sources);
        Set<String> ids = wanted.stream().map(SuggestionSourceKey::id).collect(Collectors.toSet());
        try {
            Map<SuggestionSourceKey, VoteTally> counts = new HashMap<>();
            for (VoteCountRow row : springDataRepository.countBySourceIds(ids)) {
                SuggestionSourceKey key = new SuggestionSourceKey(row.sourceType(), row.sourceId());
                if (wanted.contains(key)) {
                    counts.put(key, new VoteTally(orZero(row.worked()), orZero(row.didNotWork())));
                }
            }
            return VoteTallies.of(counts);
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to count suggestion votes", ex);
        }
    }

    private Optional<SuggestionVoteJpaEntity> findEntity(String voterId, SuggestionSourceKey source, String targetIncidentId) {
        return springDataRepository.findByVoterIdAndSourceTypeAndSourceIdAndTargetIncidentId(
                voterId, source.type(), source.id(), targetIncidentId);
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static SuggestionVote toDomain(SuggestionVoteJpaEntity entity) {
        return new SuggestionVote(entity.getVoterId(), new SuggestionSourceKey(entity.getSourceType(), entity.getSourceId()),
                entity.getTargetIncidentId(), entity.isWorked(), entity.getVotedAt());
    }
}
