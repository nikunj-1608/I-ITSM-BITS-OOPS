package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.exception.ConcurrentUpdateException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.repository.IncidentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Remaining-defects plan, Phase D (ADR-030, ADR-031): the scan runs one transaction per ticket and one failing ticket
 * does not stop the others; a violation names the target that was actually missed.
 */
class SlaServiceScanTest {

    private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

    /** Counts the transactions the scan opens and runs the callback like a real template would. */
    private static final class CountingTransactions implements TransactionOperations {
        final AtomicInteger started = new AtomicInteger();

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            started.incrementAndGet();
            return action.doInTransaction(null);
        }
    }

    private IncidentRepository repository;
    private DomainEventPublisher publisher;
    private EscalationService escalation;
    private CountingTransactions transactions;
    private SlaService scan;

    @BeforeEach
    void setUp() {
        repository = mock(IncidentRepository.class);
        publisher = mock(DomainEventPublisher.class);
        escalation = mock(EscalationService.class);
        transactions = new CountingTransactions();
        scan = serviceAt(T0.plus(Duration.ofMinutes(10)));
    }

    private SlaService serviceAt(Instant now) {
        return new SlaService(repository, publisher, escalation, Clock.fixed(now, ZoneOffset.UTC), transactions);
    }

    private static Incident p1Ticket(String id) {
        Incident incident = new Incident(id, "Payment gateway down", "Customers cannot pay at all.", "EMP-1", T0);
        PriorityDecision decision = new PriorityDecision(Priority.P1, 0.9, List.of(), List.of(), T0, null, null);
        incident.classify(IncidentCategory.APPLICATION, "SVC-APP", decision, SlaPolicy.defaultFor(Priority.P1), T0);
        incident.assign("TECH-1", T0);
        return incident;
    }

    private void tickets(Incident... incidents) {
        when(repository.findActiveIncidentIds()).thenReturn(java.util.Arrays.stream(incidents).map(Incident::getId).toList());
        for (Incident incident : incidents) {
            when(repository.findById(incident.getId())).thenReturn(Optional.of(incident));
        }
    }

    @Test
    @DisplayName("H10: every ticket is evaluated in a transaction of its own")
    void oneTransactionPerTicket() {
        tickets(p1Ticket("A-1"), p1Ticket("A-2"), p1Ticket("A-3"));

        List<SlaEvaluationResult> results = scan.evaluateAllActiveIncidents();

        assertThat(results).hasSize(3);
        assertThat(transactions.started).hasValue(3);
        verify(repository, never()).findActiveIncidents();
    }

    @Test
    @DisplayName("H10: a ticket that fails is skipped, the others are still evaluated and saved")
    void oneFailureDoesNotStopTheScan() {
        Incident first = p1Ticket("B-1");
        Incident broken = p1Ticket("B-2");
        Incident third = p1Ticket("B-3");
        tickets(first, broken, third);
        when(repository.save(broken)).thenThrow(new IllegalStateException("disk full"));

        List<SlaEvaluationResult> results = scan.evaluateAllActiveIncidents();

        assertThat(results).extracting(SlaEvaluationResult::incidentId).containsExactly("B-1", "B-3");
        verify(repository).save(first);
        verify(repository).save(third);
    }

    @Test
    @DisplayName("H10: a lost write race on one ticket is skipped quietly and the scan continues")
    void lostRaceIsSkipped() {
        Incident raced = p1Ticket("C-1");
        Incident other = p1Ticket("C-2");
        tickets(raced, other);
        when(repository.save(raced)).thenThrow(new ConcurrentUpdateException(new RuntimeException("version")));

        List<SlaEvaluationResult> results = scan.evaluateAllActiveIncidents();

        assertThat(results).extracting(SlaEvaluationResult::incidentId).containsExactly("C-2");
    }

    @Test
    @DisplayName("H10: a ticket that was closed or deleted between the id listing and its turn is skipped")
    void vanishedTicketIsSkipped() {
        Incident present = p1Ticket("D-2");
        when(repository.findActiveIncidentIds()).thenReturn(List.of("D-1", "D-2"));
        when(repository.findById("D-1")).thenReturn(Optional.empty());
        when(repository.findById("D-2")).thenReturn(Optional.of(present));

        List<SlaEvaluationResult> results = scan.evaluateAllActiveIncidents();

        assertThat(results).extracting(SlaEvaluationResult::incidentId).containsExactly("D-2");
    }

    @Test
    @DisplayName("H10: an empty scan opens no transaction")
    void emptyScan() {
        when(repository.findActiveIncidentIds()).thenReturn(List.of());

        assertThat(scan.evaluateAllActiveIncidents()).isEmpty();
        assertThat(transactions.started).hasValue(0);
    }

    @Test
    @DisplayName("M1: a ticket that missed its response target is announced as RESPONSE_TARGET")
    void responseBreachNamesTheResponseTarget() {
        Incident incident = p1Ticket("E-1"); // no response yet, 10 minutes against a 5 minute target
        tickets(incident);

        scan.evaluateAllActiveIncidents();

        assertThat(violationTargets()).containsExactly("RESPONSE_TARGET");
    }

    @Test
    @DisplayName("M1: a ticket that responded in time and then ran past its resolution target is RESOLUTION_TARGET")
    void resolutionBreachNamesTheResolutionTarget() {
        Incident incident = p1Ticket("F-1");
        incident.acknowledge("TECH-1", T0.plus(Duration.ofMinutes(2)));
        tickets(incident);

        serviceAt(T0.plus(Duration.ofMinutes(45))).evaluateAllActiveIncidents();

        assertThat(violationTargets()).containsExactly("RESOLUTION_TARGET");
    }

    @Test
    @DisplayName("M1: a ticket that already was BREACHED when it was acknowledged late announces nothing more")
    void lateAcknowledgeAfterTheScanIsSilent() {
        Incident incident = p1Ticket("G-1");
        tickets(incident);
        scan.evaluateAllActiveIncidents();
        incident.acknowledge("TECH-1", T0.plus(Duration.ofMinutes(11)));

        scan.announceTransition(incident, SlaStatus.BREACHED, T0.plus(Duration.ofMinutes(11)));

        assertThat(violationTargets()).hasSize(1);
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    private List<String> violationTargets() {
        ArgumentCaptor<DomainEvent> events = ArgumentCaptor.forClass(DomainEvent.class);
        verify(publisher, atLeast(0)).publish(events.capture());
        return events.getAllValues().stream()
                .filter(DomainEvent.SlaViolated.class::isInstance)
                .map(event -> ((DomainEvent.SlaViolated) event).targetType())
                .toList();
    }

    @Test
    @DisplayName("Control: announcing an unchanged status publishes nothing")
    void unchangedStatusIsSilent() {
        Incident incident = p1Ticket("H-1");

        scan.announceTransition(incident, incident.getSlaTracker().getStatus(), T0);

        verify(publisher, never()).publish(any());
    }
}
