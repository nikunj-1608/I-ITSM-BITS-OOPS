package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.iitsm.application.service.StrategyComparisonService.ComparisonRun;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.SlaPolicyRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.simulation.assignment.ComparisonMetric;
import com.iitsm.domain.simulation.assignment.SimulationPool;
import com.iitsm.domain.simulation.assignment.StrategyReport;
import com.iitsm.domain.simulation.assignment.TechnicianProfile;
import com.iitsm.domain.strategy.assignment.AssignmentGridAccess;
import com.iitsm.domain.strategy.assignment.AssignmentRecommendation;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.domain.strategy.assignment.RoundRobinStrategy;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The facade of the offline comparison (ADR-044, Phase L3): it only reads, inside one short transaction, and leaves the
 * registry, the live round-robin rotation and everything else exactly as it found them.
 */
class StrategyComparisonServiceTest {

    private TechnicianRepository technicianRepository;
    private SupportTeamRepository supportTeamRepository;
    private SlaPolicyRepository slaPolicyRepository;
    private AssignmentStrategyRegistry registry;
    private AtomicInteger transactions;
    private AtomicBoolean insideTransaction;
    private StrategyComparisonService service;
    private List<Technician> roster;

    @BeforeEach
    void setUp() {
        technicianRepository = mock(TechnicianRepository.class);
        supportTeamRepository = mock(SupportTeamRepository.class);
        slaPolicyRepository = mock(SlaPolicyRepository.class);
        registry = new AssignmentStrategyRegistry();
        transactions = new AtomicInteger();
        insideTransaction = new AtomicBoolean();
        SimulationPool template = SimulationPool.syntheticDefault();
        roster = template.technicians().stream().map(TechnicianProfile::toTechnician).toList();
        when(technicianRepository.findAll()).thenAnswer(call -> {
            assertThat(insideTransaction).as("the roster is read inside the transaction").isTrue();
            return roster;
        });
        for (IncidentCategory category : IncidentCategory.values()) {
            when(supportTeamRepository.findByHandledCategory(category)).thenAnswer(call -> {
                assertThat(insideTransaction).isTrue();
                return List.of(new SupportTeam(template.owningTeamOf(category), "Team", Set.of(category), "LEAD-1"));
            });
        }
        when(slaPolicyRepository.findDefaultByPriority(any())).thenReturn(Optional.empty());
        service = newService(registry);
    }

    private StrategyComparisonService newService(AssignmentStrategyRegistry strategyRegistry) {
        TransactionOperations counting = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                transactions.incrementAndGet();
                insideTransaction.set(true);
                try {
                    return action.doInTransaction(mock(TransactionStatus.class));
                } finally {
                    insideTransaction.set(false);
                }
            }
        };
        return new StrategyComparisonService(strategyRegistry, technicianRepository, supportTeamRepository,
                slaPolicyRepository, counting);
    }

    @Test
    @DisplayName("it reads the roster once, inside one transaction, and writes nothing")
    void readsOnce() {
        ComparisonRun run = service.compare(42L, 100, 2, 20.0, null);

        assertThat(run.poolSource()).isEqualTo("ROSTER");
        assertThat(run.report().technicianCount()).isEqualTo(12);
        assertThat(run.report().strategies()).hasSize(5);
        assertThat(run.runMillis()).isGreaterThanOrEqualTo(0);
        assertThat(transactions).hasValue(1);
        verify(technicianRepository, times(1)).findAll();
        verifyNoMoreInteractions(technicianRepository);
        verify(technicianRepository, never()).save(any());
        verify(supportTeamRepository, times(IncidentCategory.values().length)).findByHandledCategory(any());
        verifyNoMoreInteractions(supportTeamRepository);
        verify(slaPolicyRepository, times(Priority.values().length)).findDefaultByPriority(any());
        verifyNoMoreInteractions(slaPolicyRepository);
    }

    @Test
    @DisplayName("the roster order does not matter: the result is the same whatever order the database returns")
    void rosterOrderDoesNotChangeTheResult() {
        ComparisonRun inOrder = service.compare(42L, 150, 2, 25.0, null);
        List<Technician> reversed = new java.util.ArrayList<>(roster);
        java.util.Collections.reverse(reversed);
        org.mockito.Mockito.doAnswer(call -> reversed).when(technicianRepository).findAll();

        ComparisonRun reversedRun = service.compare(42L, 150, 2, 25.0, null);

        assertThat(reversedRun.report()).isEqualTo(inOrder.report());
    }

    @Test
    @DisplayName("an empty roster falls back to the synthetic pool and says so")
    void emptyRosterUsesTheSyntheticPool() {
        org.mockito.Mockito.doReturn(List.of()).when(technicianRepository).findAll();

        ComparisonRun run = service.compare(null, 60, 1, null, null);

        assertThat(run.poolSource()).isEqualTo("SYNTHETIC");
        assertThat(run.report().technicianCount()).isEqualTo(12);
        assertThat(run.report().parameters().ticketCount()).isEqualTo(60);
        assertThat(run.report().parameters().arrivalsPerHour()).isEqualTo(15.0);
    }

    @Test
    @DisplayName("a bad parameter or an unknown strategy is refused before anything is read")
    void validationComesFirst() {
        assertThatThrownBy(() -> service.compare(1L, 0, 1, 15.0, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.compare(1L, 100, 51, 15.0, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.compare(1L, 100, 1, 15.0, List.of("FASTEST")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("FASTEST");

        verifyNoInteractions(technicianRepository, supportTeamRepository, slaPolicyRepository);
        assertThat(transactions).hasValue(0);
    }

    @Test
    @DisplayName("the registry and the live round-robin rotation are exactly as before, and the live strategies are the same objects")
    void registryAndRotationAreUntouched() {
        AssignmentStrategyRegistry reg = registry;
        reg.setActiveStrategy("ROUND_ROBIN");
        AssignmentStrategy live = reg.getActiveStrategy();
        List<AssignmentStrategy> before = reg.getAllStrategies();
        Incident ticket = AssignmentGridAccess.ticket();
        List<Technician> candidates = AssignmentGridAccess.allTechnicians();
        live.recommend(ticket, candidates);
        live.recommend(ticket, candidates);
        String turn = live.rank(ticket, candidates).get(0).technicianId();

        service.compare(42L, 200, 3, 20.0, List.of("ROUND_ROBIN", "LOAD_BASED"));

        assertThat(reg.getActiveStrategyName()).isEqualTo("ROUND_ROBIN");
        assertThat(reg.getActiveStrategy()).isSameAs(live);
        assertThat(live.rank(ticket, candidates).get(0).technicianId()).as("the live rotation did not move").isEqualTo(turn);
        assertThat(reg.getAllStrategies()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(live).isInstanceOf(RoundRobinStrategy.class);
    }

    @Test
    @DisplayName("Open/Closed: a strategy registered later is compared automatically, without any change to the service")
    void newlyRegisteredStrategyIsCompared() {
        registry.register(new AssignmentStrategy() {
            @Override
            public AssignmentRecommendation recommend(Incident incident, List<Technician> candidates) {
                return new AssignmentRecommendation(candidates.get(0), 0.5, "FIRST_COME", "first");
            }

            @Override
            public String getStrategyName() {
                return "FIRST_COME";
            }

            @Override
            public String getDescription() {
                return "always the first candidate";
            }
        });

        ComparisonRun all = service.compare(1L, 80, 2, 20.0, null);
        ComparisonRun only = service.compare(1L, 80, 2, 20.0, List.of("first_come", "skill_based"));

        assertThat(all.report().strategies()).extracting(StrategyReport::strategyName).contains("FIRST_COME").hasSize(6);
        assertThat(only.report().strategies()).extracting(StrategyReport::strategyName)
                .containsExactly("FIRST_COME", "SKILL_BASED");
        assertThat(registry.getActiveStrategyName()).as("registering does not activate").isEqualTo("WEIGHTED_COMPOSITE");
    }

    @Test
    @DisplayName("the SLA breach rate uses the resolution targets of the repository's policies")
    void usesTheConfiguredTargets() {
        for (Priority priority : Priority.values()) {
            org.mockito.Mockito.doReturn(Optional.of(
                    new SlaPolicy("P-" + priority, priority, null, Duration.ofSeconds(30), Duration.ofMinutes(1), 0.75)))
                    .when(slaPolicyRepository).findDefaultByPriority(priority);
        }

        ComparisonRun run = service.compare(5L, 80, 2, 15.0, null);

        for (StrategyReport strategy : run.report().strategies()) {
            assertThat(strategy.summaries().get(ComparisonMetric.SLA_BREACH_RATE).mean())
                    .as("a one-minute target is always missed (%s)", strategy.strategyName()).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("the service cannot touch a clock, publish an event, send a notification or write: its constructor says so")
    void constructorHasNoWayToWrite() {
        Constructor<?>[] constructors = StrategyComparisonService.class.getConstructors();
        assertThat(constructors).hasSize(1);
        List<String> types = Arrays.stream(constructors[0].getParameterTypes()).map(Class::getSimpleName).toList();

        assertThat(types).containsExactly("AssignmentStrategyRegistry", "TechnicianRepository", "SupportTeamRepository",
                "SlaPolicyRepository", "TransactionOperations");
        assertThat(Arrays.stream(StrategyComparisonService.class.getDeclaredFields()).map(f -> f.getType()))
                .noneMatch(Clock.class::isAssignableFrom).noneMatch(DomainEventPublisher.class::isAssignableFrom);
    }
}
