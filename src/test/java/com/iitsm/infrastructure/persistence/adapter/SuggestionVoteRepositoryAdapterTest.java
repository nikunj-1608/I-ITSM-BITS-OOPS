package com.iitsm.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.repository.ResolvedCaseRepository;
import com.iitsm.domain.repository.SuggestionVoteRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceKey;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;
import com.iitsm.domain.strategy.suggestion.SuggestionVote;
import com.iitsm.domain.strategy.suggestion.VoteTallies;
import com.iitsm.domain.strategy.suggestion.VoteTally;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** ADR-041: votes on H2, written through the adapter; the rows of this class are deleted afterwards. */
@SpringBootTest
@ActiveProfiles("test")
class SuggestionVoteRepositoryAdapterTest {

    private static final String PREFIX = "SVT-";
    private static final String TARGET_1 = PREFIX + "T1";
    private static final String TARGET_2 = PREFIX + "T2";
    private static final String SOURCE_ID = PREFIX + "SRC";
    private static final SuggestionSourceKey SOURCE = new SuggestionSourceKey(SuggestionSourceType.RESOLVED_TICKET, SOURCE_ID);
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    @Autowired private SuggestionVoteRepository votes;
    @Autowired private ResolvedCaseRepository resolvedCases;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        cleanUp();
        ticket(TARGET_1, "IN_PROGRESS", false);
        ticket(TARGET_2, "IN_PROGRESS", false);
        ticket(SOURCE_ID, "CLOSED", true);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM suggestion_votes WHERE target_incident_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM resolutions WHERE incident_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM incidents WHERE id LIKE ?", PREFIX + "%");
    }

    private void ticket(String id, String status, boolean resolved) {
        jdbc.update("INSERT INTO incidents (id, title, description, reporter_id, category, status, created_at) "
                + "VALUES (?, 'Title', 'Description', 'EMP-DEMO-01', 'NETWORK', ?, ?)", id, status, Timestamp.from(NOW));
        if (resolved) {
            jdbc.update("INSERT INTO resolutions (incident_id, root_cause, steps_taken, preventive_action, confirmed_category, "
                    + "resolved_by, resolved_at) VALUES (?, 'r', 's', 'p', 'NETWORK', 'TECH-NET-1', ?)", id, Timestamp.from(NOW));
        }
    }

    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM suggestion_votes WHERE target_incident_id LIKE ?", Integer.class, PREFIX + "%");
    }

    @Test
    @DisplayName("A vote is stored and read back by its key")
    void storesAndFinds() {
        votes.save(new SuggestionVote("TECH-NET-1", SOURCE, TARGET_1, true, NOW));

        SuggestionVote found = votes.findVote("TECH-NET-1", SOURCE, TARGET_1).orElseThrow();
        assertThat(found.worked()).isTrue();
        assertThat(found.votedAt().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(NOW);
        assertThat(votes.findVote("TECH-NET-2", SOURCE, TARGET_1)).isEmpty();
    }

    @Test
    @DisplayName("Saving the same key again updates the one row (upsert)")
    void upsert() {
        votes.save(new SuggestionVote("TECH-NET-1", SOURCE, TARGET_1, true, NOW));
        votes.save(new SuggestionVote("TECH-NET-1", SOURCE, TARGET_1, false, NOW.plusSeconds(30)));

        assertThat(rows()).isEqualTo(1);
        assertThat(votes.findVote("TECH-NET-1", SOURCE, TARGET_1).orElseThrow().worked()).isFalse();
    }

    @Test
    @DisplayName("The table refuses a second row for the same voter, source and ticket")
    void uniqueConstraint() {
        votes.save(new SuggestionVote("TECH-NET-1", SOURCE, TARGET_1, true, NOW));

        assertThatThrownBy(() -> jdbc.update("INSERT INTO suggestion_votes (voter_id, source_type, source_id, "
                + "target_incident_id, worked, voted_at) VALUES ('TECH-NET-1', 'RESOLVED_TICKET', ?, ?, FALSE, ?)",
                SOURCE_ID, TARGET_1, Timestamp.from(NOW))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Counts are per source over every ticket and voter, and only for the sources asked for")
    void tallies() {
        votes.save(new SuggestionVote("TECH-NET-1", SOURCE, TARGET_1, true, NOW));
        votes.save(new SuggestionVote("TECH-NET-2", SOURCE, TARGET_1, true, NOW));
        votes.save(new SuggestionVote("TECH-NET-1", SOURCE, TARGET_2, false, NOW));
        SuggestionSourceKey article = new SuggestionSourceKey(SuggestionSourceType.KB_ARTICLE, SOURCE_ID);
        votes.save(new SuggestionVote("TECH-NET-3", article, TARGET_2, true, NOW));

        VoteTallies onlyTicket = votes.tallies(List.of(SOURCE));
        assertThat(onlyTicket.of(SOURCE)).isEqualTo(new VoteTally(2, 1));
        assertThat(onlyTicket.of(article)).as("not asked for").isEqualTo(VoteTally.none());
        assertThat(votes.tallies(List.of(SOURCE, article)).of(article)).isEqualTo(new VoteTally(1, 0));
        assertThat(votes.tallies(List.of()).of(SOURCE)).isEqualTo(VoteTally.none());
    }

    @Test
    @DisplayName("Deleting the target ticket deletes its votes (ON DELETE CASCADE)")
    void cascadeOnTicketDelete() {
        votes.save(new SuggestionVote("TECH-NET-1", SOURCE, TARGET_1, true, NOW));

        jdbc.update("DELETE FROM incidents WHERE id = ?", TARGET_1);

        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("Only a ticket with a confirmed resolution is a resolved case")
    void isResolvedCase() {
        assertThat(resolvedCases.isResolvedCase(SOURCE_ID)).isTrue();
        assertThat(resolvedCases.isResolvedCase(TARGET_1)).as("open ticket").isFalse();
        assertThat(resolvedCases.isResolvedCase(PREFIX + "MISSING")).isFalse();
        assertThat(resolvedCases.isResolvedCase(" ")).isFalse();
        jdbc.update("UPDATE incidents SET status = 'IN_PROGRESS' WHERE id = ?", SOURCE_ID);
        assertThat(resolvedCases.isResolvedCase(SOURCE_ID)).as("rejected fix, resolution still stored").isFalse();
    }
}
