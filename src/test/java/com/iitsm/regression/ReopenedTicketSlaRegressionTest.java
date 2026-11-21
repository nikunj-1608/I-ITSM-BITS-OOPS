package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.application.service.EscalationService;
import com.iitsm.application.service.SlaEvaluationResult;
import com.iitsm.application.service.SlaService;
import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.repository.IncidentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * H5 regression: the scheduled SLA scan must keep watching a ticket after the employee rejected the
 * fix. Before the fix the tracker stayed at MET, so a reopened ticket never warned, breached or
 * escalated.
 */
@Tag("regression")
class ReopenedTicketSlaRegressionTest {

    private Instant t0;
    private IncidentRepository incidentRepository;
    private DomainEventPublisher eventPublisher;
    private EscalationService escalationService;
    private Incident incident;

    @BeforeEach
    void setUp() {
        t0 = Instant.parse("2026-11-03T10:00:00Z");
        incidentRepository = mock(IncidentRepository.class);
        eventPublisher = mock(DomainEventPublisher.class);
        escalationService = mock(EscalationService.class);

        incident = new Incident(
                "FIN-2026-000001", "Payment gateway down", "Customers cannot pay, gateway returns errors.",
                "EMP-1", t0);
        PriorityDecision decision = new PriorityDecision(Priority.P1, 0.9, List.of(), List.of(), t0, null, null);
        incident.classify(IncidentCategory.APPLICATION, "SVC-APP", decision, SlaPolicy.defaultFor(Priority.P1), t0);
        incident.assign("TECH-1", t0);
        incident.acknowledge("TECH-1", t0.plusSeconds(60));
        incident.startInvestigation("TECH-1", t0.plusSeconds(120));
        when(incidentRepository.findActiveIncidents()).thenReturn(List.of(incident));
        when(incidentRepository.findActiveIncidentIds()).thenReturn(List.of(incident.getId()));
        when(incidentRepository.findById(incident.getId())).thenReturn(java.util.Optional.of(incident));
    }

    private Instant at(long minutes) {
        return t0.plus(Duration.ofMinutes(minutes));
    }

    private SlaService serviceAt(Instant now) {
        return new SlaService(incidentRepository, eventPublisher, escalationService, Clock.fixed(now, ZoneOffset.UTC), org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    }

    private void resolveThenReject(long resolveAt, long rejectAt) {
        Resolution resolution = new Resolution("Root cause", "Steps", "Prevention", "SOFTWARE", "TECH-1", at(resolveAt));
        incident.resolve(resolution, at(resolveAt));
        incident.reject("EMP-1", "Still failing", at(rejectAt));
    }

    @Test
    @DisplayName("H5: the SLA scan flags a reopened ticket that passes its deadline and publishes SlaViolated")
    void scanBreachesReopenedTicket() {
        resolveThenReject(10, 12);

        List<SlaEvaluationResult> results = serviceAt(at(60)).evaluateAllActiveIncidents();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).currentStatus()).isEqualTo(SlaStatus.BREACHED);
        assertThat(results.get(0).statusChanged()).isTrue();
        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(DomainEvent.SlaViolated.class);
        verify(incidentRepository).save(incident);
    }

    @Test
    @DisplayName("H5: the SLA scan publishes SlaWarning when a reopened ticket nears its deadline")
    void scanWarnsForReopenedTicket() {
        resolveThenReject(10, 12); // deadline 10:32

        SlaEvaluationResult result = serviceAt(at(25)).evaluateAllActiveIncidents().get(0);

        assertThat(result.currentStatus()).isEqualTo(SlaStatus.AT_RISK);
        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(DomainEvent.SlaWarning.class);
    }

    @Test
    @DisplayName("H5: the escalation check runs for the reopened ticket after it breaches")
    void scanEscalatesReopenedTicket() {
        resolveThenReject(10, 12);

        serviceAt(at(60)).evaluateAllActiveIncidents();

        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);
        verify(escalationService).evaluateAndEscalate(incident.getId());
    }

    @Test
    @DisplayName("H5: a reopened ticket far from its deadline raises no event")
    void scanIsQuietEarlyInTheWindow() {
        resolveThenReject(10, 12);

        SlaEvaluationResult result = serviceAt(at(14)).evaluateAllActiveIncidents().get(0);

        assertThat(result.currentStatus()).isEqualTo(SlaStatus.ON_TRACK);
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("H5: a ticket that was verified is never re-breached by the scan")
    void verifiedTicketIsNotTouchedByTheScan() {
        Resolution resolution = new Resolution("Root cause", "Steps", "Prevention", "SOFTWARE", "TECH-1", at(10));
        incident.resolve(resolution, at(10));
        incident.verify("EMP-1", at(12));

        SlaEvaluationResult result = serviceAt(at(600)).evaluateAllActiveIncidents().get(0);

        assertThat(result.currentStatus()).isEqualTo(SlaStatus.MET);
        verify(eventPublisher, never()).publish(any());
    }
}
