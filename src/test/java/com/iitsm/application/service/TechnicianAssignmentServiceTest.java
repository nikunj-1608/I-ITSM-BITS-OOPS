package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.domain.exception.TechnicianOverloadedException;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.strategy.assignment.AssignmentRecommendation;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.PoolKind;
import com.iitsm.domain.strategy.assignment.RoutedCandidates;
import com.iitsm.domain.strategy.assignment.WeightedCompositeStrategy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class TechnicianAssignmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private TechnicianRepository technicianRepository;
    private AssignmentStrategy strategy;
    private AssignmentDecisionRecorder recorder;
    private TechnicianAssignmentService service;
    private Incident incident;

    @BeforeEach
    void setUp() {
        technicianRepository = mock(TechnicianRepository.class);
        strategy = mock(AssignmentStrategy.class);
        recorder = mock(AssignmentDecisionRecorder.class);
        service = new TechnicianAssignmentService(technicianRepository, recorder);

        incident = new Incident("INC-1", "VPN keeps dropping", "The VPN client drops every hour for all of us.", "EMP-1", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null);
        incident.classify(IncidentCategory.NETWORK, "SVC-VPN", decision, SlaPolicy.defaultFor(Priority.P3), NOW);
    }

    private static Technician technician(String id, int maxCapacity) {
        return new Technician(id, "Tech " + id, new Email(id.toLowerCase() + "@test.com"), "hash",
                Set.of(new Skill("BGP", IncidentCategory.NETWORK)), 5, maxCapacity, "TEAM-NET");
    }

    private static RoutedCandidates team(Technician... technicians) {
        return new RoutedCandidates(PoolKind.OWNING_TEAM, List.of(technicians));
    }

    private void recommend(Technician technician) {
        when(strategy.recommend(any(), any())).thenReturn(new AssignmentRecommendation(technician, 1.0, "test", "STUB"));
    }

    @Test
    @DisplayName("Assigns the recommended technician and persists the updated workload")
    void assignsRecommendedTechnician() {
        Technician tech = technician("TECH-1", 3);
        recommend(tech);
        when(technicianRepository.findById("TECH-1")).thenReturn(Optional.of(tech));

        Optional<String> assigned = service.tryAssignTechnician(incident, team(tech), strategy, "SYSTEM", NOW);

        assertThat(assigned).contains("TECH-1");
        assertThat(tech.getActiveIncidentIds()).contains("INC-1");
        verify(technicianRepository).save(tech);
    }

    @Test
    @DisplayName("Returns empty without saving when the freshly reloaded technician is already full")
    void returnsEmptyWhenFreshTechnicianIsFull() {
        Technician stale = technician("TECH-1", 1);
        Technician full = technician("TECH-1", 1);
        full.assignIncident("INC-OTHER");
        recommend(stale);
        when(technicianRepository.findById("TECH-1")).thenReturn(Optional.of(full));

        assertThat(service.tryAssignTechnician(incident, team(stale), strategy, "SYSTEM", NOW)).isEmpty();
        verify(technicianRepository, never()).save(any());
    }

    @Test
    @DisplayName("Absorbs only the expected capacity race (TechnicianOverloadedException)")
    void absorbsCapacityRace() {
        Technician tech = technician("TECH-1", 3);
        recommend(tech);
        Technician racing = mock(Technician.class);
        when(racing.getId()).thenReturn("TECH-1");
        when(racing.hasCapacity()).thenReturn(true);
        doThrow(new TechnicianOverloadedException("TECH-1", 3, 3)).when(racing).assignIncident("INC-1");
        when(technicianRepository.findById("TECH-1")).thenReturn(Optional.of(racing));

        assertThat(service.tryAssignTechnician(incident, team(tech), strategy, "SYSTEM", NOW)).isEmpty();
    }

    @Test
    @DisplayName("ADR-026: it joins the callers transaction and refuses to run without one (no slot committed on its own)")
    void joinsTheCallersTransaction() throws NoSuchMethodException {
        Transactional transactional = TechnicianAssignmentService.class
                .getMethod("tryAssignTechnician", Incident.class, RoutedCandidates.class, AssignmentStrategy.class,
                        String.class, Instant.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .as("REQUIRES_NEW committed the slot before the caller did and leaked it on any later rollback")
                .isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("Unexpected failures propagate instead of being swallowed")
    void unexpectedFailuresPropagate() {
        Technician tech = technician("TECH-1", 3);
        recommend(tech);
        when(technicianRepository.findById("TECH-1")).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.tryAssignTechnician(incident, team(tech), strategy, "SYSTEM", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
    }

    @Test
    @DisplayName("ADR-044: the decision is recorded with the strategy's own ranking, the pool and the actor")
    void recordsTheDecisionItWasMadeFrom() {
        Technician first = technician("TECH-1", 3);
        Technician second = technician("TECH-2", 3);
        second.assignIncident("INC-OTHER");
        when(technicianRepository.findById("TECH-1")).thenReturn(Optional.of(first));
        RoutedCandidates overflow = new RoutedCandidates(PoolKind.SKILLED_OVERFLOW, List.of(first, second));
        AssignmentStrategy composite = org.mockito.Mockito.spy(new WeightedCompositeStrategy());

        assertThat(service.tryAssignTechnician(incident, overflow, composite, "DISPATCHER", NOW)).contains("TECH-1");

        org.mockito.ArgumentCaptor<AssignmentRecommendation> chosen = org.mockito.ArgumentCaptor.forClass(AssignmentRecommendation.class);
        verify(recorder).recordStrategyChoice(eq("INC-1"), eq(PoolKind.SKILLED_OVERFLOW), chosen.capture(), eq("DISPATCHER"), eq(NOW));
        assertThat(chosen.getValue().technician()).isSameAs(first);
        assertThat(chosen.getValue().ranking()).extracting(c -> c.technicianId()).containsExactly("TECH-1", "TECH-2");
        verify(composite, org.mockito.Mockito.times(1)).recommend(same(incident), any());
        verify(composite, never()).rank(any(), any());
    }

    @Test
    @DisplayName("ADR-044: no decision is recorded when no slot was taken")
    void noDecisionWithoutASlot() {
        Technician stale = technician("TECH-1", 1);
        Technician full = technician("TECH-1", 1);
        full.assignIncident("INC-OTHER");
        recommend(stale);
        when(technicianRepository.findById("TECH-1")).thenReturn(Optional.of(full));

        assertThat(service.tryAssignTechnician(incident, team(stale), strategy, "SYSTEM", NOW)).isEmpty();
        assertThat(service.tryAssignTechnician(incident, RoutedCandidates.none(), strategy, "SYSTEM", NOW)).isEmpty();
        verify(recorder, never()).recordStrategyChoice(any(), any(), any(), any(), any());
    }
}
