package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TechnicianAssignmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private TechnicianRepository technicianRepository;
    private AssignmentStrategy strategy;
    private TechnicianAssignmentService service;
    private Incident incident;

    @BeforeEach
    void setUp() {
        technicianRepository = mock(TechnicianRepository.class);
        strategy = mock(AssignmentStrategy.class);
        service = new TechnicianAssignmentService(technicianRepository);

        incident = new Incident("INC-1", "VPN keeps dropping", "The VPN client drops every hour for all of us.", "EMP-1", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null);
        incident.classify(IncidentCategory.NETWORK, "SVC-VPN", decision, SlaPolicy.defaultFor(Priority.P3), NOW);
    }

    private static Technician technician(String id, int maxCapacity) {
        return new Technician(id, "Tech " + id, new Email(id.toLowerCase() + "@test.com"), "hash",
                Set.of(new Skill("BGP", IncidentCategory.NETWORK)), 5, maxCapacity, "TEAM-NET");
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

        Optional<String> assigned = service.tryAssignTechnician(incident, List.of(tech), strategy, NOW);

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

        assertThat(service.tryAssignTechnician(incident, List.of(stale), strategy, NOW)).isEmpty();
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

        assertThat(service.tryAssignTechnician(incident, List.of(tech), strategy, NOW)).isEmpty();
    }

    @Test
    @DisplayName("Unexpected failures propagate instead of being swallowed")
    void unexpectedFailuresPropagate() {
        Technician tech = technician("TECH-1", 3);
        recommend(tech);
        when(technicianRepository.findById("TECH-1")).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.tryAssignTechnician(incident, List.of(tech), strategy, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
    }
}
