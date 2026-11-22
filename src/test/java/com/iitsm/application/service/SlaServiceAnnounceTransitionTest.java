package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.SlaTracker;
import com.iitsm.domain.repository.IncidentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * H6, phase 3: {@code SlaService.announceTransition} publishes exactly the event the scan would have published
 * for a status change that already happened (the priority override moves the tracker itself).
 */
class SlaServiceAnnounceTransitionTest {

    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");

    private DomainEventPublisher eventPublisher;
    private SlaService slaService;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(DomainEventPublisher.class);
        slaService = new SlaService(mock(IncidentRepository.class), eventPublisher,
                mock(EscalationService.class), Clock.fixed(T0, ZoneOffset.UTC), org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    }

    private Incident incidentWithStatus(SlaStatus current) {
        Incident incident = new Incident("FIN-2026-000001", "Valid Title", "Valid description that is long enough.", "EMP-1", T0);
        incident.setSlaTracker(SlaTracker.reconstitute(T0, T0.plus(Duration.ofMinutes(5)), T0.plus(Duration.ofMinutes(30)),
                null, null, Duration.ZERO, null, current));
        return incident;
    }

    static Stream<Arguments> everyStatusPair() {
        return Stream.of(SlaStatus.values()).flatMap(prev ->
                Stream.of(SlaStatus.values()).map(curr -> Arguments.of(prev, curr)));
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @MethodSource("everyStatusPair")
    void publishesTheRightEventForEveryStatusPair(SlaStatus previous, SlaStatus current) {
        Incident incident = incidentWithStatus(current);

        slaService.announceTransition(incident, previous, T0.plusSeconds(60));

        if (previous == current) {
            verify(eventPublisher, never()).publish(any());
        } else if (current == SlaStatus.AT_RISK || current == SlaStatus.WARNING) {
            ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
            verify(eventPublisher).publish(captor.capture());
            assertThat(captor.getValue()).isInstanceOf(DomainEvent.SlaWarning.class);
        } else if (current == SlaStatus.BREACHED) {
            ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
            verify(eventPublisher).publish(captor.capture());
            assertThat(captor.getValue()).isInstanceOf(DomainEvent.SlaViolated.class);
        } else {
            verify(eventPublisher, never()).publish(any());
        }
    }

    @Test
    @DisplayName("SlaViolated names the incident, the resolution target and the time of the announcement")
    void violationCarriesItsFacts() {
        Incident incident = incidentWithStatus(SlaStatus.BREACHED);
        Instant now = T0.plusSeconds(90);

        slaService.announceTransition(incident, SlaStatus.ON_TRACK, now);

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(captor.capture());
        DomainEvent.SlaViolated event = (DomainEvent.SlaViolated) captor.getValue();
        assertThat(event.incidentId()).isEqualTo("FIN-2026-000001");
        assertThat(event.targetType()).isEqualTo("RESOLUTION_TARGET");
        assertThat(event.breachedAt()).isEqualTo(now);
        assertThat(event.occurredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("SlaWarning names the incident and the new status")
    void warningCarriesItsFacts() {
        Incident incident = incidentWithStatus(SlaStatus.WARNING);

        slaService.announceTransition(incident, SlaStatus.ON_TRACK, T0.plusSeconds(90));

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(captor.capture());
        DomainEvent.SlaWarning event = (DomainEvent.SlaWarning) captor.getValue();
        assertThat(event.incidentId()).isEqualTo("FIN-2026-000001");
        assertThat(event.message()).contains("FIN-2026-000001").contains(SlaStatus.WARNING.getDescription());
    }

    @Test
    @DisplayName("a second announcement whose previous status is already the current one publishes nothing more")
    void secondAnnouncementForUnchangedStatusIsSilent() {
        Incident incident = incidentWithStatus(SlaStatus.BREACHED);

        slaService.announceTransition(incident, SlaStatus.ON_TRACK, T0);
        slaService.announceTransition(incident, SlaStatus.BREACHED, T0);

        verify(eventPublisher, org.mockito.Mockito.times(1)).publish(any());
    }
}
