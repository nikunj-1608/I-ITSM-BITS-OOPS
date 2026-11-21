package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.NotResolvedException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import com.iitsm.domain.strategy.suggestion.CandidatePool;
import com.iitsm.domain.strategy.suggestion.SuggestionCandidate;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** ADR-042: promotion rules, the article's invariants, personal-data removal and the candidate pool. */
class KnowledgeArticleTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final String TICKET = "FIN-2026-000042";
    private static final String RESOLVER = "TECH-NET-1";

    private static final Employee REPORTER = new Employee("EMP-7", "Priya Sharma", new Email("priya.sharma@nimbusfinserve.com"),
            "hash", Designation.ASSOCIATE, "Finance", 3);
    private static final Technician RESOLVING_TECH = new Technician(RESOLVER, "Tech One", new Email("t1@test.com"), "hash",
            Set.of(), 5, 5, "TEAM-NET");
    private static final Technician OTHER_TECH = new Technician("TECH-NET-2", "Tech Two", new Email("t2@test.com"), "hash",
            Set.of(), 5, 5, "TEAM-NET");
    private static final TeamLead LEAD = new TeamLead("LEAD-NET", "Lead", new Email("lead@test.com"), "hash", Set.of(), 9, 5, "TEAM-NET");
    private static final Administrator ADMIN = new Administrator("ADMIN-1", "Admin", new Email("admin@test.com"), "hash",
            AdminLevel.SYSTEM_ADMIN);

    private static Incident ticket(IncidentStatus status, boolean withResolution) {
        Incident incident = new Incident(TICKET, "VPN broken for Priya Sharma in Finance",
                "Hi, this is Priya (priya.sharma@nimbusfinserve.com, Finance). The AnyConnect VPN gateway refuses my tunnel.",
                REPORTER.getId(), NOW.minusSeconds(3600));
        incident.setCategory(IncidentCategory.NETWORK);
        if (withResolution) {
            incident.setResolution(new Resolution("Expired certificate on the VPN gateway; Priya's laptop was fine",
                    "Renewed the gateway certificate, called priya.sharma@nimbusfinserve.com to confirm",
                    "Certificate expiry alert", "NETWORK", RESOLVER, NOW.minusSeconds(60)));
        }
        incident.transitionTo(status);
        return incident;
    }

    private static KnowledgeArticle promote(Incident source, User actor, String title) {
        return KnowledgeArticle.promote(source, actor, title, PersonalDataRedactor.forReporter(REPORTER), NOW);
    }

    @Nested
    @DisplayName("who may promote")
    class Who {

        @Test
        @DisplayName("The technician who resolved it, any team lead and any administrator may promote")
        void allowed() {
            assertThat(promote(ticket(IncidentStatus.RESOLVED, true), RESOLVING_TECH, null).getCreatedBy()).isEqualTo(RESOLVER);
            assertThat(promote(ticket(IncidentStatus.VERIFIED, true), LEAD, null).getCreatedBy()).isEqualTo("LEAD-NET");
            assertThat(promote(ticket(IncidentStatus.CLOSED, true), ADMIN, null).getCreatedBy()).isEqualTo("ADMIN-1");
        }

        @Test
        @DisplayName("Another technician and an employee are refused (403)")
        void refused() {
            assertThatThrownBy(() -> promote(ticket(IncidentStatus.RESOLVED, true), OTHER_TECH, null))
                    .isInstanceOf(UnauthorizedOperationException.class);
            assertThatThrownBy(() -> promote(ticket(IncidentStatus.RESOLVED, true), REPORTER, null))
                    .isInstanceOf(UnauthorizedOperationException.class);
        }

        @Test
        @DisplayName("The polymorphic rule: nobody without a resolution")
        void noResolutionNoPromotion() {
            assertThat(RESOLVING_TECH.canPromoteResolution(null)).isFalse();
            assertThat(LEAD.canPromoteResolution(null)).isFalse();
            assertThat(ADMIN.canPromoteResolution(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("which ticket")
    class Which {

        @ParameterizedTest
        @EnumSource(value = IncidentStatus.class, names = {"RESOLVED", "VERIFIED", "CLOSED"}, mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("A ticket without a confirmed resolution is refused (409 ERR_NOT_RESOLVED), even with a stored report")
        void notResolved(IncidentStatus status) {
            assertThatThrownBy(() -> promote(ticket(status, true), ADMIN, null))
                    .isInstanceOf(NotResolvedException.class)
                    .extracting("errorCode").isEqualTo("ERR_NOT_RESOLVED");
        }

        @Test
        @DisplayName("A closed ticket without a resolution report is refused")
        void noReport() {
            assertThatThrownBy(() -> promote(ticket(IncidentStatus.CLOSED, false), ADMIN, null))
                    .isInstanceOf(NotResolvedException.class);
        }
    }

    @Nested
    @DisplayName("what is copied")
    class Content {

        @Test
        @DisplayName("The technical content is copied with the reporter's name, e-mail and department removed")
        void personalDataRemoved() {
            KnowledgeArticle article = promote(ticket(IncidentStatus.CLOSED, true), ADMIN, null);
            String all = String.join(" | ", article.getTitle(), article.getProblem(), article.getRootCause(),
                    article.getStepsTaken(), article.getPreventiveAction());

            assertThat(all.toLowerCase()).doesNotContain("priya").doesNotContain("sharma").doesNotContain("finance")
                    .doesNotContain("nimbusfinserve");
            assertThat(article.getProblem()).contains("AnyConnect VPN gateway refuses my tunnel");
            assertThat(article.getRootCause()).startsWith("Expired certificate on the VPN gateway");
            assertThat(article.getStepsTaken()).startsWith("Renewed the gateway certificate");
            assertThat(article.getPreventiveAction()).isEqualTo("Certificate expiry alert");
        }

        @Test
        @DisplayName("Id, category, source and time come from the ticket and the clock; the requested title wins")
        void identity() {
            KnowledgeArticle article = promote(ticket(IncidentStatus.RESOLVED, true), RESOLVING_TECH, "  Renew the VPN certificate ");

            assertThat(article.getId()).isEqualTo("KB-" + TICKET);
            assertThat(article.getCategory()).isEqualTo(IncidentCategory.NETWORK);
            assertThat(article.getSourceIncidentId()).isEqualTo(TICKET);
            assertThat(article.getCreatedAt()).isEqualTo(NOW);
            assertThat(article.getTitle()).isEqualTo("Renew the VPN certificate");
        }

        @Test
        @DisplayName("A blank requested title means the ticket's title (redacted); a title of personal data only becomes neutral")
        void titles() {
            assertThat(promote(ticket(IncidentStatus.RESOLVED, true), ADMIN, " ").getTitle())
                    .isEqualTo("VPN broken for [removed] in [removed]");
            assertThat(promote(ticket(IncidentStatus.RESOLVED, true), ADMIN, "Priya Sharma").getTitle())
                    .isEqualTo("Fix from ticket " + TICKET);
        }

        @Test
        @DisplayName("An article needs every field; the title is at most 255 characters")
        void invariants() {
            assertThatThrownBy(() -> new KnowledgeArticle("KB-1", " ", IncidentCategory.NETWORK, "p", "r", "s", "a", "T", "U", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new KnowledgeArticle("KB-1", "t", null, "p", "r", "s", "a", "T", "U", NOW))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new KnowledgeArticle("KB-1", "x".repeat(256), IncidentCategory.NETWORK, "p", "r", "s", "a", "T", "U", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new KnowledgeArticle("KB-1", "t", IncidentCategory.NETWORK, "p", "r", "s", "a", "T", "U", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("redactor")
    class Redactor {

        private final PersonalDataRedactor redactor = PersonalDataRedactor.forReporter(REPORTER);

        @Test
        @DisplayName("Whole words only, ignoring case: 'Financeportal' and 'priyanka' stay, 'FINANCE' and 'Priya' go")
        void wholeWordsIgnoringCase() {
            assertThat(redactor.redact("FINANCE team, Financeportal, priyanka and Priya"))
                    .isEqualTo("[removed] team, Financeportal, priyanka and [removed]");
        }

        @Test
        @DisplayName("The e-mail address and its local part are removed")
        void email() {
            assertThat(redactor.redact("mail priya.sharma@nimbusfinserve.com or priya.sharma"))
                    .isEqualTo("mail [removed] or [removed]");
        }

        @Test
        @DisplayName("Short name parts are kept (fewer than 3 letters); no reporter means no change")
        void shortPartsAndNoReporter() {
            Employee shortName = new Employee("EMP-8", "Al Wu", new Email("aw@x.com"), "hash", Designation.ASSOCIATE, "Legal", 1);
            assertThat(PersonalDataRedactor.forReporter(shortName).redact("Al and Wu met Al Wu")).isEqualTo("Al and Wu met [removed]");
            assertThat(PersonalDataRedactor.forReporter(null).redact("Priya")).isEqualTo("Priya");
            assertThat(redactor.redact(null)).isNull();
        }
    }

    @Test
    @DisplayName("Incident.canBePromotedBy follows the same rules as promote (ADR-043: the card's 'promotable' flag)")
    void promotableFlagMatchesPromote() {
        Incident resolved = ticket(IncidentStatus.RESOLVED, true);
        assertThat(resolved.canBePromotedBy(RESOLVING_TECH)).isTrue();
        assertThat(resolved.canBePromotedBy(LEAD)).isTrue();
        assertThat(resolved.canBePromotedBy(ADMIN)).isTrue();
        assertThat(resolved.canBePromotedBy(OTHER_TECH)).isFalse();
        assertThat(resolved.canBePromotedBy(REPORTER)).isFalse();
        assertThat(resolved.canBePromotedBy(null)).isFalse();
        assertThat(ticket(IncidentStatus.IN_PROGRESS, true).canBePromotedBy(ADMIN)).isFalse();
        assertThat(ticket(IncidentStatus.CLOSED, false).canBePromotedBy(ADMIN)).isFalse();
    }

    @Test
    @DisplayName("Candidate pool: a promoted ticket is offered once, as its article; other tickets stay")
    void candidatePool() {
        SuggestionCandidate promoted = new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, "T1",
                IncidentCategory.NETWORK, Priority.P2, "t", "", "r", "s", "p");
        SuggestionCandidate other = new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, "T2",
                IncidentCategory.NETWORK, Priority.P2, "t", "", "r", "s", "p");
        SuggestionCandidate article = new SuggestionCandidate(SuggestionSourceType.KB_ARTICLE, "KB-T1",
                IncidentCategory.NETWORK, null, "t", "", "r", "s", "p", "T1");

        List<SuggestionCandidate> pool = CandidatePool.of(List.of(promoted, other), List.of(article)).candidates();

        assertThat(pool).containsExactly(article, other);
        assertThatThrownBy(() -> pool.add(other)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(EnumSet.allOf(SuggestionSourceType.class)).hasSize(2);
    }
}
