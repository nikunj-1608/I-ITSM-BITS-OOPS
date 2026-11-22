package com.iitsm.domain.strategy.suggestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** ADR-040: invariants of the suggestion value objects, the query built from a ticket and the status helper. */
class SuggestionValueObjectsTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    @Test
    @DisplayName("The query for a ticket excludes the ticket and every linked duplicate, and joins title and description")
    void queryForIncident() {
        Incident incident = new Incident("NET-2026-000005", "VPN drops", "The vpn gateway drops every hour.", "EMP-1", NOW);
        incident.setCategory(IncidentCategory.NETWORK);
        incident.linkIncident("NET-2026-000001");
        incident.linkIncident("NET-2026-000009");

        SuggestionQuery query = SuggestionQuery.forIncident(incident);

        assertThat(query.text()).isEqualTo("VPN drops The vpn gateway drops every hour.");
        assertThat(query.category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(query.excludedTicketIds())
                .containsExactlyInAnyOrder("NET-2026-000005", "NET-2026-000001", "NET-2026-000009");
    }

    @Test
    @DisplayName("Exclusion is by origin ticket: an article promoted from the ticket or a duplicate is excluded too (ADR-042)")
    void exclusionIsByOrigin() {
        SuggestionQuery query = new SuggestionQuery("T1", IncidentCategory.NETWORK, "x", Set.of("T1", "DUP"));
        SuggestionCandidate ownArticle = new SuggestionCandidate(SuggestionSourceType.KB_ARTICLE, "KB-T1",
                IncidentCategory.NETWORK, null, "t", "", "r", "s", "p", "T1");
        SuggestionCandidate duplicateArticle = new SuggestionCandidate(SuggestionSourceType.KB_ARTICLE, "KB-DUP",
                IncidentCategory.NETWORK, null, "t", "", "r", "s", "p", "DUP");
        SuggestionCandidate otherArticle = new SuggestionCandidate(SuggestionSourceType.KB_ARTICLE, "KB-T7",
                IncidentCategory.NETWORK, null, "t", "", "r", "s", "p", "T7");

        assertThat(query.excludes(ownArticle)).isTrue();
        assertThat(query.excludes(duplicateArticle)).isTrue();
        assertThat(query.excludes(otherArticle)).isFalse();
    }

    @Test
    @DisplayName("A ticket candidate is its own origin; the article window defaults to the ticket window")
    void defaults() {
        SuggestionCandidate ticket = new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, "T9",
                IncidentCategory.NETWORK, Priority.P2, "t", "", "r", "s", "p");
        assertThat(ticket.originIncidentId()).isEqualTo("T9");
        assertThat(new SuggestionPolicy(3, 0.2, 1, true, 150, 5).articleWindow()).isEqualTo(150);
        assertThatThrownBy(() -> new SuggestionPolicy(3, 0.2, 1, true, 150, 5, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The query keeps its own copy of the exclusions")
    void queryCopiesExclusions() {
        Set<String> excluded = new HashSet<>(Set.of("A"));
        SuggestionQuery query = new SuggestionQuery("T1", IncidentCategory.NETWORK, "x", excluded);
        excluded.add("B");

        assertThat(query.excludedTicketIds()).containsExactly("A");
        assertThatThrownBy(() -> query.excludedTicketIds().add("C")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("The matching text is title, problem, root cause and steps; the preventive action is not compared")
    void matchingText() {
        SuggestionCandidate candidate = new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, "T1",
                IncidentCategory.NETWORK, Priority.P2, " Title ", null, "Root", "Steps", "Prevent");

        assertThat(candidate.matchingText()).isEqualTo("Title Root Steps");
        assertThat(candidate.problemText()).isEmpty();
    }

    @Test
    @DisplayName("A candidate needs a source type and a source id")
    void candidateInvariants() {
        assertThatThrownBy(() -> new SuggestionCandidate(null, "T1", null, null, "", "", "", "", ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, " ", null, null, "", "", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A suggestion's similarity is in [0, 1] and its terms are an unmodifiable copy")
    void suggestionInvariants() {
        SuggestionCandidate candidate = new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, "T1",
                IncidentCategory.NETWORK, Priority.P2, "t", "", "r", "s", "p");
        assertThatThrownBy(() -> new ResolutionSuggestion(candidate, 1.01, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResolutionSuggestion(candidate, -0.01, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ResolutionSuggestion(candidate, 0.5, null).matchedTerms()).isEmpty();
    }

    @Test
    @DisplayName("Policy values outside their range are refused at start-up")
    void policyInvariants() {
        assertThatThrownBy(() -> new SuggestionPolicy(0, 0.2, 1, true, 200, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SuggestionPolicy(3, 0.0, 1, true, 200, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SuggestionPolicy(3, 1.1, 1, true, 200, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SuggestionPolicy(3, 0.2, -1, true, 200, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SuggestionPolicy(3, 0.2, 1, true, 0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SuggestionPolicy(3, 0.2, 1, true, 200, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The candidate window is the ticket's category with the filter on, every category with it off")
    void windowCategory() {
        SuggestionQuery query = new SuggestionQuery("T1", IncidentCategory.DATABASE, "x", Set.of());

        assertThat(new SuggestionPolicy(3, 0.2, 1, true, 200, 5).windowCategory(query)).isEqualTo(IncidentCategory.DATABASE);
        assertThat(new SuggestionPolicy(3, 0.2, 1, false, 200, 5).windowCategory(query)).isNull();
    }

    @ParameterizedTest
    @EnumSource(IncidentStatus.class)
    @DisplayName("Only RESOLVED, VERIFIED and CLOSED tickets have a confirmed resolution")
    void confirmedResolutionStatuses(IncidentStatus status) {
        Set<IncidentStatus> expected = EnumSet.of(IncidentStatus.RESOLVED, IncidentStatus.VERIFIED, IncidentStatus.CLOSED);

        assertThat(status.hasConfirmedResolution()).isEqualTo(expected.contains(status));
        assertThat(IncidentStatus.withConfirmedResolution().contains(status)).isEqualTo(expected.contains(status));
    }
}
