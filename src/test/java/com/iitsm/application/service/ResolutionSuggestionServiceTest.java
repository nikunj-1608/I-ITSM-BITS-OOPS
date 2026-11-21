package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.domain.exception.ResourceNotFoundException;
import com.iitsm.domain.exception.ServiceNotFoundException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.repository.KnowledgeArticleRepository;
import com.iitsm.domain.repository.ResolvedCaseRepository;
import com.iitsm.domain.repository.SuggestionVoteRepository;
import com.iitsm.domain.strategy.suggestion.ResolutionSuggester;
import com.iitsm.domain.strategy.suggestion.ResolutionSuggestion;
import com.iitsm.domain.strategy.suggestion.SuggestionCandidate;
import com.iitsm.domain.strategy.suggestion.SuggestionPolicy;
import com.iitsm.domain.strategy.suggestion.SuggestionQuery;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceKey;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;
import com.iitsm.domain.strategy.suggestion.SuggestionVote;
import com.iitsm.domain.strategy.suggestion.VoteTallies;
import com.iitsm.domain.strategy.suggestion.VoteTally;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * ADR-040, ADR-041: the suggestion service orchestrates only. It loads the ticket through the lifecycle service (so
 * access and 404 rules apply), reads the bounded candidate window and its vote counts, hands them to whichever
 * {@link ResolutionSuggester} is wired, and stores votes as an upsert keyed by voter, source and ticket.
 */
class ResolutionSuggestionServiceTest {

    private static final String TICKET = "NET-2026-000042";
    private static final String TECH = "TECH-NET-1";
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final SuggestionPolicy POLICY = new SuggestionPolicy(3, 0.2, 1, true, 200, 5);
    private static final SuggestionSourceKey SOURCE = new SuggestionSourceKey(SuggestionSourceType.RESOLVED_TICKET, "NET-2026-000007");

    private IncidentLifecycleService lifecycle;
    private ResolvedCaseRepository cases;
    private SuggestionVoteRepository votes;
    private KnowledgeArticleRepository articles;
    private ResolutionSuggester suggester;
    private Incident incident;

    @BeforeEach
    void setUp() {
        lifecycle = mock(IncidentLifecycleService.class);
        cases = mock(ResolvedCaseRepository.class);
        votes = mock(SuggestionVoteRepository.class);
        articles = mock(KnowledgeArticleRepository.class);
        suggester = mock(ResolutionSuggester.class);
        incident = new Incident(TICKET, "VPN drops", "The vpn gateway drops users.", "EMP-1", NOW);
        incident.setCategory(IncidentCategory.NETWORK);
        when(votes.tallies(any())).thenReturn(VoteTallies.none());
        when(articles.findNewestArticles(any(), anyInt())).thenReturn(List.of());
    }

    private ResolutionSuggestionService service(ResolutionSuggester ranking, SuggestionPolicy policy) {
        return new ResolutionSuggestionService(lifecycle, cases, votes, articles, ranking, policy, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ResolutionSuggestionService service() {
        return service(suggester, POLICY);
    }

    private static SuggestionCandidate candidate(String id) {
        return new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, id, IncidentCategory.NETWORK, Priority.P3,
                "VPN gateway", "", "Expired certificate", "Renewed it", "Alert");
    }

    @Nested
    @DisplayName("suggestions")
    class Suggestions {

        @Test
        @DisplayName("The window is the newest candidate-window tickets of the ticket's category, ranked with their votes")
        void readsTheBoundedWindowAndRanks() {
            List<SuggestionCandidate> window = List.of(candidate("A"), candidate("B"));
            VoteTallies tallies = VoteTallies.of(Map.of(candidate("A").key(), new VoteTally(2, 0)));
            List<ResolutionSuggestion> ranked = List.of(new ResolutionSuggestion(candidate("A"), 0.7, List.of("vpn")));
            when(lifecycle.getIncident(TECH, TICKET)).thenReturn(incident);
            when(cases.findNewestResolvedCases(IncidentCategory.NETWORK, 200)).thenReturn(window);
            when(votes.tallies(List.of(candidate("A").key(), candidate("B").key()))).thenReturn(tallies);
            when(suggester.suggest(any(SuggestionQuery.class), any(), any(VoteTallies.class))).thenReturn(ranked);

            assertThat(service().suggestFor(TECH, TICKET)).isEqualTo(ranked);
            verify(suggester).suggest(SuggestionQuery.forIncident(incident), window, tallies);
        }

        @Test
        @DisplayName("A ticket the actor may not view is refused before anything is read (403)")
        void accessIsEnforcedFirst() {
            when(lifecycle.getIncident(TECH, TICKET)).thenThrow(new UnauthorizedOperationException("not yours"));

            assertThatThrownBy(() -> service().suggestFor(TECH, TICKET)).isInstanceOf(UnauthorizedOperationException.class);
            verify(cases, never()).findNewestResolvedCases(any(), anyInt());
        }

        @Test
        @DisplayName("An unknown ticket is a 404 and reads nothing")
        void unknownTicket() {
            when(lifecycle.getIncident(TECH, "NOPE")).thenThrow(new ServiceNotFoundException("Incident not found: NOPE"));

            assertThatThrownBy(() -> service().suggestFor(TECH, "NOPE")).isInstanceOf(ServiceNotFoundException.class);
            verify(cases, never()).findNewestResolvedCases(any(), anyInt());
        }

        @Test
        @DisplayName("A ticket without a category gets an empty list without a query while the category filter is on")
        void uncategorisedTicket() {
            Incident fresh = new Incident(TICKET, "Something", "Something is odd with my computer today.", "EMP-1", NOW);
            when(lifecycle.getIncident(TECH, TICKET)).thenReturn(fresh);

            assertThat(service().suggestFor(TECH, TICKET)).isEmpty();
            verify(cases, never()).findNewestResolvedCases(any(), anyInt());
        }

        @Test
        @DisplayName("With the category filter off, the window covers every category")
        void filterOffReadsEveryCategory() {
            when(lifecycle.getIncident(TECH, TICKET)).thenReturn(incident);
            when(cases.findNewestResolvedCases(null, 50)).thenReturn(List.of());

            service(suggester, new SuggestionPolicy(3, 0.2, 1, false, 50, 5)).suggestFor(TECH, TICKET);

            verify(cases).findNewestResolvedCases(null, 50);
        }

        @Test
        @DisplayName("Articles of the category join the window; a promoted ticket is offered once, as its article")
        void articlesJoinTheWindowAndReplaceTheirTicket() {
            SuggestionCandidate promotedTicket = candidate("NET-2026-000007");
            SuggestionCandidate other = candidate("NET-2026-000008");
            SuggestionCandidate article = new SuggestionCandidate(SuggestionSourceType.KB_ARTICLE, "KB-NET-2026-000007",
                    IncidentCategory.NETWORK, null, "VPN gateway", "", "Expired certificate", "Renewed it", "Alert",
                    "NET-2026-000007");
            when(lifecycle.getIncident(TECH, TICKET)).thenReturn(incident);
            when(cases.findNewestResolvedCases(IncidentCategory.NETWORK, 200)).thenReturn(List.of(promotedTicket, other));
            when(articles.findNewestArticles(IncidentCategory.NETWORK, 50)).thenReturn(List.of(article));
            when(suggester.suggest(any(SuggestionQuery.class), any(), any(VoteTallies.class))).thenReturn(List.of());

            service(suggester, new SuggestionPolicy(3, 0.2, 1, true, 200, 5, 50)).suggestFor(TECH, TICKET);

            verify(suggester).suggest(SuggestionQuery.forIncident(incident), List.of(article, other), VoteTallies.none());
        }

        /**
         * Open/Closed proof (CLAUDE.md rule 5): a second, tiny ranking is used in place of the TF-IDF one without
         * changing the service. It suggests every candidate whose title shares a word with the ticket title.
         */
        @Test
        @DisplayName("Extension point: a different ResolutionSuggester plugs in without changing the service")
        void anotherSuggesterPlugsIn() {
            ResolutionSuggester titleWords = new ResolutionSuggester() {
                @Override
                public List<ResolutionSuggestion> suggest(
                        SuggestionQuery query, Collection<SuggestionCandidate> candidates, VoteTallies tallies) {
                    List<String> words = List.of(query.text().toLowerCase().split("\\W+"));
                    return candidates.stream()
                            .filter(c -> List.of(c.title().toLowerCase().split("\\W+")).stream().anyMatch(words::contains))
                            .map(c -> new ResolutionSuggestion(c, 0.5, List.of("title")))
                            .toList();
                }
            };
            SuggestionCandidate printer = new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, "P",
                    IncidentCategory.NETWORK, Priority.P4, "Printer offline", "", "r", "s", "p");
            when(lifecycle.getIncident(TECH, TICKET)).thenReturn(incident);
            when(cases.findNewestResolvedCases(IncidentCategory.NETWORK, 200)).thenReturn(List.of(candidate("A"), printer));

            assertThat(service(titleWords, POLICY).suggestFor(TECH, TICKET))
                    .extracting(s -> s.source().sourceId()).containsExactly("A");
        }
    }

    @Nested
    @DisplayName("votes")
    class Votes {

        @BeforeEach
        void viewable() {
            when(lifecycle.getIncident(TECH, TICKET)).thenReturn(incident);
        }

        @Test
        @DisplayName("A first vote is stored with the voter's id, the ticket's id and the clock's time")
        void firstVote() {
            when(cases.isResolvedCase(SOURCE.id())).thenReturn(true);
            when(votes.findVote(TECH, SOURCE, TICKET)).thenReturn(Optional.empty());
            when(votes.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(votes.tallies(List.of(SOURCE))).thenReturn(VoteTallies.of(Map.of(SOURCE, new VoteTally(1, 0))));

            var result = service().vote(TECH, TICKET, SOURCE, true);

            assertThat(result.vote()).isEqualTo(new SuggestionVote(TECH, SOURCE, TICKET, true, NOW));
            assertThat(result.tally()).isEqualTo(new VoteTally(1, 0));
        }

        @Test
        @DisplayName("Voting again changes the earlier verdict instead of adding a second one")
        void secondVoteReplacesTheFirst() {
            SuggestionVote earlier = new SuggestionVote(TECH, SOURCE, TICKET, true, NOW.minusSeconds(60));
            when(cases.isResolvedCase(SOURCE.id())).thenReturn(true);
            when(votes.findVote(TECH, SOURCE, TICKET)).thenReturn(Optional.of(earlier));
            when(votes.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service().vote(TECH, TICKET, SOURCE, false);

            ArgumentCaptor<SuggestionVote> saved = ArgumentCaptor.forClass(SuggestionVote.class);
            verify(votes).save(saved.capture());
            assertThat(saved.getValue()).isEqualTo(new SuggestionVote(TECH, SOURCE, TICKET, false, NOW));
        }

        @Test
        @DisplayName("A source that is not a resolved ticket is 404 ERR_NOT_FOUND and nothing is stored")
        void unknownSource() {
            when(cases.isResolvedCase(SOURCE.id())).thenReturn(false);

            assertThatThrownBy(() -> service().vote(TECH, TICKET, SOURCE, true))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .extracting("errorCode").isEqualTo("ERR_NOT_FOUND");
            verify(votes, never()).save(any());
        }

        @Test
        @DisplayName("An article source must exist in the knowledge base: unknown is 404, known is stored (ADR-042)")
        void articleSource() {
            SuggestionSourceKey article = new SuggestionSourceKey(SuggestionSourceType.KB_ARTICLE, "KB-NET-2026-000007");
            when(articles.existsById("KB-NET-2026-000007")).thenReturn(false);
            assertThatThrownBy(() -> service().vote(TECH, TICKET, article, true)).isInstanceOf(ResourceNotFoundException.class);
            verify(votes, never()).save(any());

            when(articles.existsById("KB-NET-2026-000007")).thenReturn(true);
            when(votes.findVote(TECH, article, TICKET)).thenReturn(Optional.empty());
            when(votes.save(any())).thenAnswer(inv -> inv.getArgument(0));
            assertThat(service().vote(TECH, TICKET, article, true).vote().source()).isEqualTo(article);
        }

        @Test
        @DisplayName("A ticket the voter may not view is refused (403) before the source is even looked up")
        void voteNeedsAccessToTheTicket() {
            when(lifecycle.getIncident("TECH-DB-1", TICKET)).thenThrow(new UnauthorizedOperationException("not yours"));

            assertThatThrownBy(() -> service().vote("TECH-DB-1", TICKET, SOURCE, true))
                    .isInstanceOf(UnauthorizedOperationException.class);
            verify(cases, never()).isResolvedCase(any());
            verify(votes, never()).save(any());
        }

        @Test
        @DisplayName("A ticket voted as its own fix is a 400 validation error")
        void selfVote() {
            SuggestionSourceKey self = new SuggestionSourceKey(SuggestionSourceType.RESOLVED_TICKET, TICKET);
            when(cases.isResolvedCase(TICKET)).thenReturn(true);
            when(votes.findVote(TECH, self, TICKET)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().vote(TECH, TICKET, self, true)).isInstanceOf(ValidationException.class);
            verify(votes, never()).save(any());
        }
    }
}
