package com.iitsm.domain.strategy.similarity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.application.service.DuplicateDetectionService;
import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.strategy.nlp.WordListDomainVocabulary;
import com.iitsm.domain.strategy.nlp.TfIdfVectorizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for similarity measures and DuplicateDetectionService.
 */
class DuplicateDetectionTest {

    private IncidentRepository incidentRepository;
    private DomainEventPublisher eventPublisher;
    private DuplicateDetectionService duplicateService;
    private Instant now;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        eventPublisher = mock(DomainEventPublisher.class);
        now = Instant.parse("2026-10-26T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        TfIdfVectorizer vectorizer = new TfIdfVectorizer();
        vectorizer.train(List.of(
                "Cannot connect to corporate Cisco AnyConnect VPN gateway timeout",
                "Exchange server high CPU alert mail delivery delay",
                "Laptop battery overheating and swelling chassis"
        ));
        CosineTfIdfSimilarity similarity = new CosineTfIdfSimilarity(vectorizer);

        DuplicateMatchPolicy policy = new DuplicateMatchPolicy(similarity, WordListDomainVocabulary.duplicateVocabulary(), 0.30, 1);
        duplicateService = new DuplicateDetectionService(incidentRepository, eventPublisher, policy, 72, clock);
    }

    private PriorityDecision dummyDecision() {
        return new PriorityDecision(Priority.P2, 0.50, List.of(), List.of(), now, null, null);
    }

    @Test
    @DisplayName("JaccardSimilarity computes set intersection over union correctly")
    void testJaccardSimilarity() {
        JaccardSimilarity jaccard = new JaccardSimilarity();
        double sim = jaccard.calculateSimilarity(
                "PostgreSQL database connection pool exhausted",
                "Database connection pool exhausted on PostgreSQL server"
        );
        assertThat(sim).isGreaterThan(0.70);

        double dissim = jaccard.calculateSimilarity(
                "PostgreSQL database deadlock",
                "Laptop screen flickering black"
        );
        assertThat(dissim).isLessThan(0.20);
    }

    @Test
    @DisplayName("Detects a duplicate (threshold plus shared domain term) and links bidirectionally")
    void testDuplicateDetectionAndLinking() {
        Incident parent = new Incident(
                "INC-PARENT",
                "Cannot connect to corporate Cisco AnyConnect VPN gateway timeout",
                "AnyConnect client dropped connection with gateway timeout error",
                "EMP-0001",
                now.minusSeconds(3600)
        );
        parent.classify(IncidentCategory.NETWORK, "SRV-VPN", dummyDecision(), SlaPolicy.defaultFor(Priority.P2), now);

        when(incidentRepository.findOpenIncidentsByServiceAndCategorySince(eq("SRV-VPN"), eq(IncidentCategory.NETWORK), any()))
                .thenReturn(List.of(parent));

        Incident duplicate = new Incident(
                "INC-NEW",
                "AnyConnect VPN connection failed gateway timeout",
                "Cannot connect to corporate Cisco AnyConnect VPN due to gateway timeout",
                "EMP-0002",
                now
        );
        duplicate.classify(IncidentCategory.NETWORK, "SRV-VPN", dummyDecision(), SlaPolicy.defaultFor(Priority.P2), now);

        Optional<DuplicateDetectionService.DuplicateMatch> match = duplicateService.checkAndLinkDuplicate(duplicate);

        assertThat(match).isPresent();
        assertThat(match.get().parentIncidentId()).isEqualTo("INC-PARENT");
        assertThat(match.get().similarityScore()).isGreaterThanOrEqualTo(0.30);

        // Verify bidirectional link
        assertThat(parent.getLinkedIncidentIds()).contains("INC-NEW");
        assertThat(duplicate.getLinkedIncidentIds()).contains("INC-PARENT");

        // Verify DuplicateDetected event published
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(DomainEvent.DuplicateDetected.class);
    }

    @Test
    @DisplayName("Does not link dissimilar tickets")
    void testDissimilarNotLinked() {
        Incident parent = new Incident(
                "INC-PARENT",
                "Exchange server high CPU alert mail delivery delay",
                "Mail queue building up on exchange node",
                "EMP-0001",
                now.minusSeconds(3600)
        );
        parent.classify(IncidentCategory.APPLICATION, "SRV-EXCHANGE", dummyDecision(), SlaPolicy.defaultFor(Priority.P2), now);

        when(incidentRepository.findOpenIncidentsByServiceAndCategorySince(eq("SRV-BENEFITS"), eq(IncidentCategory.APPLICATION), any()))
                .thenReturn(List.of(parent));

        Incident distinct = new Incident(
                "INC-DISTINCT",
                "Benefits portal open enrollment form not submitting",
                "Clicking submit displays error 500 on benefits page",
                "EMP-0002",
                now
        );
        distinct.classify(IncidentCategory.APPLICATION, "SRV-BENEFITS", dummyDecision(), SlaPolicy.defaultFor(Priority.P2), now);

        Optional<DuplicateDetectionService.DuplicateMatch> match = duplicateService.checkAndLinkDuplicate(distinct);
        assertThat(match).isEmpty();
        assertThat(parent.getLinkedIncidentIds()).doesNotContain("INC-DISTINCT");
        assertThat(distinct.getLinkedIncidentIds()).doesNotContain("INC-PARENT");
    }

    private Incident ticket(String id, String title, String description, String reporter, Instant createdAt, String service) {
        Incident incident = new Incident(id, title, description, reporter, createdAt);
        incident.classify(IncidentCategory.NETWORK, service, dummyDecision(), SlaPolicy.defaultFor(Priority.P2), now);
        return incident;
    }

    @Test
    @DisplayName("ADR-016: no all-time category fallback - a similar ticket on another service is not a candidate")
    void noCategoryWideFallback() {
        Incident other = ticket("INC-OTHER", "Cannot connect to corporate Cisco AnyConnect VPN gateway timeout",
                "AnyConnect client dropped connection with gateway timeout error", "EMP-0001", now.minusSeconds(3600), "SRV-WIFI");
        when(incidentRepository.findByCategory(IncidentCategory.NETWORK)).thenReturn(List.of(other));
        when(incidentRepository.findOpenIncidentsByServiceAndCategorySince(eq("SRV-VPN"), eq(IncidentCategory.NETWORK), any()))
                .thenReturn(List.of());
        Incident fresh = ticket("INC-NEW", "Cannot connect to corporate Cisco AnyConnect VPN gateway timeout",
                "AnyConnect client dropped connection with gateway timeout error", "EMP-0002", now, "SRV-VPN");

        assertThat(duplicateService.checkAndLinkDuplicate(fresh)).isEmpty();
        verify(incidentRepository, never()).findByCategory(any());
    }

    @Test
    @DisplayName("ADR-016: the recency window comes from configuration and bounds the candidate query and the loop")
    void recencyWindowIsConfigurable() {
        TfIdfVectorizer vectorizer = new TfIdfVectorizer();
        vectorizer.train(List.of("vpn gateway timeout", "laptop battery swelling"));
        DuplicateMatchPolicy policy = new DuplicateMatchPolicy(new CosineTfIdfSimilarity(vectorizer), WordListDomainVocabulary.duplicateVocabulary(), 0.30, 1);
        DuplicateDetectionService oneHour = new DuplicateDetectionService(
                incidentRepository, eventPublisher, policy, 1, Clock.fixed(now, ZoneOffset.UTC));
        Incident old = ticket("INC-OLD", "VPN gateway timeout again", "VPN gateway timeout reported on the branch link",
                "EMP-0001", now.minusSeconds(7200), "SRV-VPN");
        when(incidentRepository.findOpenIncidentsByServiceAndCategorySince(any(), any(), any())).thenReturn(List.of(old));
        Incident fresh = ticket("INC-NEW", "VPN gateway timeout again", "VPN gateway timeout reported on the branch link",
                "EMP-0002", now, "SRV-VPN");

        assertThat(oneHour.checkAndLinkDuplicate(fresh)).isEmpty();
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(incidentRepository).findOpenIncidentsByServiceAndCategorySince(any(), any(), cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(now.minusSeconds(3600));
    }

    @Test
    @DisplayName("ADR-016: chain A-B-C - C matches B but not the root A, so it links to B only")
    void chainDoesNotDriftIntoRootCluster() {
        Incident a = ticket("INC-A", "VPN tunnel flapping at branch", "The VPN tunnel flaps constantly at the Pune branch office",
                "EMP-0001", now.minusSeconds(7200), "SRV-VPN");
        Incident b = ticket("INC-B", "VPN tunnel flapping and keyboard dead at branch",
                "The VPN tunnel flaps constantly and the keyboard stopped working at the Pune branch office",
                "EMP-0002", now.minusSeconds(3600), "SRV-VPN");
        a.linkIncident("INC-B");
        b.linkIncident("INC-A");
        Incident c = ticket("INC-C", "Keyboard dead at branch office", "The keyboard stopped working at the Pune branch office desk",
                "EMP-0003", now, "SRV-VPN");
        when(incidentRepository.findOpenIncidentsByServiceAndCategorySince(any(), any(), any())).thenReturn(List.of(a, b));
        when(incidentRepository.findById("INC-A")).thenReturn(Optional.of(a));
        when(incidentRepository.findById("INC-B")).thenReturn(Optional.of(b));

        Optional<DuplicateDetectionService.DuplicateMatch> match = duplicateService.checkAndLinkDuplicate(c);

        assertThat(match).isPresent();
        assertThat(match.get().parentIncidentId()).isEqualTo("INC-B");
        assertThat(c.getLinkedIncidentIds()).containsExactly("INC-B");
        assertThat(a.getLinkedIncidentIds()).doesNotContain("INC-C");
        assertThat(b.getLinkedIncidentIds()).contains("INC-C");
    }
}
