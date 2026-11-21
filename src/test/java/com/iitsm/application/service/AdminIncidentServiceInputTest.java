package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.exception.ServiceNotFoundException;
import com.iitsm.domain.model.Administrator;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.policy.AccessPolicy;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.ReassignmentRequestRepository;
import com.iitsm.domain.repository.SlaPolicyRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.domain.strategy.classification.ClassificationResult;
import com.iitsm.domain.strategy.classification.IncidentClassifier;
import com.iitsm.domain.strategy.priority.CompositePriorityCalculator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase C1 (ADR-027, decisions D5 and D6): an unknown service id is a 404, a missing category means the classifier's suggestion. */
class AdminIncidentServiceInputTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String TICKET = "FIN-2026-000142";
    private static final String UNKNOWN_SERVICE = "SVC-DOES-NOT-EXIST";

    private IncidentRepository incidentRepository;
    private ItServiceRepository itServiceRepository;
    private IncidentClassifier classifier;
    private DomainEventPublisher eventPublisher;
    private AdminIncidentService service;
    private Incident ticket;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        itServiceRepository = mock(ItServiceRepository.class);
        classifier = mock(IncidentClassifier.class);
        eventPublisher = mock(DomainEventPublisher.class);
        CompositePriorityCalculator calculator = mock(CompositePriorityCalculator.class);
        SlaPolicyRepository slaPolicyRepository = mock(SlaPolicyRepository.class);
        service = new AdminIncidentService(incidentRepository, userRepository, mock(TechnicianRepository.class),
                slaPolicyRepository, itServiceRepository, mock(SupportTeamRepository.class),
                mock(ReassignmentRequestRepository.class), eventPublisher, mock(AccessPolicy.class), classifier, calculator,
                mock(AssignmentStrategyRegistry.class), routerWithNobodyFree(), mock(TechnicianAssignmentService.class),
                mock(SlaService.class), mock(AssignmentDecisionRecorder.class), Clock.fixed(NOW, ZoneOffset.UTC));

        Administrator admin = mock(Administrator.class);
        when(admin.getId()).thenReturn(ADMIN_ID);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        ticket = new Incident(TICKET, "VPN gateway drops", "The vpn gateway keeps dropping connections for everyone.", "EMP-1", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null);
        ticket.classify(IncidentCategory.APPLICATION, "SVC-VPN", decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        ticket.setNeedsTriage(true);
        when(incidentRepository.findById(TICKET)).thenReturn(Optional.of(ticket));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(call -> call.getArgument(0));
        when(itServiceRepository.findById(UNKNOWN_SERVICE)).thenReturn(Optional.empty());
        when(calculator.calculate(any(), any(), any(), any(), any())).thenReturn(decision);
        when(slaPolicyRepository.findDefaultByPriority(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("D6: reclassify with an unknown service id is refused as not found; nothing is changed, saved or published")
    void reclassifyUnknownService() {
        assertThatThrownBy(() -> service.reclassify(TICKET, ADMIN_ID, IncidentCategory.SECURITY, UNKNOWN_SERVICE, "reason"))
                .isInstanceOf(ServiceNotFoundException.class).hasMessageContaining(UNKNOWN_SERVICE);

        assertThat(ticket.getCategory()).isEqualTo(IncidentCategory.APPLICATION);
        assertThat(ticket.getServiceId()).isEqualTo("SVC-VPN");
        assertThat(ticket.isNeedsTriage()).isTrue();
        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("D6: accept-suggestion with an unknown service id is refused as not found and changes nothing")
    void acceptSuggestionUnknownService() {
        assertThatThrownBy(() -> service.acceptTriageSuggestion(TICKET, ADMIN_ID, IncidentCategory.NETWORK, UNKNOWN_SERVICE))
                .isInstanceOf(ServiceNotFoundException.class);

        assertThat(ticket.getCategory()).isEqualTo(IncidentCategory.APPLICATION);
        assertThat(ticket.isNeedsTriage()).isTrue();
        verify(incidentRepository, never()).save(any());
    }

    @Test
    @DisplayName("D6: no service id at all is still fine (the dialog sends none)")
    void noServiceIdIsAllowed() {
        service.reclassify(TICKET, ADMIN_ID, IncidentCategory.NETWORK, null, "reason");

        assertThat(ticket.getCategory()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(ticket.getServiceId()).as("the ticket keeps its service").isEqualTo("SVC-VPN");
    }

    @Test
    @DisplayName("D5: without a category, accept-suggestion uses what the classifier makes of the ticket's text")
    void missingCategoryUsesTheSuggestion() {
        when(classifier.classify(ticket.getTitle(), ticket.getDescription()))
                .thenReturn(ClassificationResult.of(IncidentCategory.NETWORK, 0.4, "TEST", List.of()));

        service.acceptTriageSuggestion(TICKET, ADMIN_ID, null, null);

        assertThat(ticket.getCategory()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(ticket.isNeedsTriage()).isFalse();
    }

    @Test
    @DisplayName("D5: an explicit category always wins and the classifier is not asked")
    void explicitCategoryWins() {
        service.acceptTriageSuggestion(TICKET, ADMIN_ID, IncidentCategory.STORAGE, null);

        assertThat(ticket.getCategory()).isEqualTo(IncidentCategory.STORAGE);
        verifyNoInteractions(classifier);
    }

    /** A mocked router that offers nobody (Mockito would answer null for the RoutedCandidates of ADR-044). */
    private static TicketRouter routerWithNobodyFree() {
        return org.mockito.Mockito.mock(TicketRouter.class, invocation ->
                invocation.getMethod().getReturnType() == com.iitsm.domain.strategy.assignment.RoutedCandidates.class
                        ? com.iitsm.domain.strategy.assignment.RoutedCandidates.none()
                        : org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation));
    }
}
