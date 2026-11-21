package com.iitsm.application.service;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.policy.RoutingPolicy;
import com.iitsm.domain.repository.SlaPolicyRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.simulation.assignment.ComparisonParameters;
import com.iitsm.domain.simulation.assignment.ComparisonReport;
import com.iitsm.domain.simulation.assignment.SimulationPool;
import com.iitsm.domain.simulation.assignment.StrategyComparison;
import com.iitsm.domain.simulation.assignment.TechnicianProfile;
import com.iitsm.domain.simulation.assignment.WorkTimeModel;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Pattern: Facade over the offline strategy comparison (ADR-044, Phase L3). Validates the parameters, reads three inputs
 * once and read-only (the roster, who owns each category, the resolution target of each priority), runs the pure
 * in-memory experiment of {@link StrategyComparison} and returns its report with the run time.
 *
 * <p>It writes nothing: it has no repository that writes, no event publisher, no notification channel, no clock and no
 * way to change the registry's active strategy. It reads {@link AssignmentStrategyRegistry#getAllStrategies()} only, so a
 * newly registered strategy is compared automatically (Open/Closed), and replays each strategy through its
 * {@link AssignmentStrategy#freshInstance() fresh instance}, so the live round-robin rotation never moves. The database
 * is read in one short read-only transaction, never while the simulation runs. The run time is measured with
 * {@link System#nanoTime()}, an elapsed-time counter, not with the application clock (which the simulation must not read).
 */
@Service
public class StrategyComparisonService {

    /** The pool was copied from the current roster. */
    public static final String POOL_ROSTER = "ROSTER";
    /** There was no usable roster; the invented pool was used. */
    public static final String POOL_SYNTHETIC = "SYNTHETIC";

    private static final long NANOS_PER_MILLI = 1_000_000L;

    /**
     * @param report     the result; equal for equal input (it holds no timing)
     * @param poolSource {@link #POOL_ROSTER} or {@link #POOL_SYNTHETIC}
     * @param runMillis  how long the experiment took, in milliseconds
     */
    public record ComparisonRun(ComparisonReport report, String poolSource, long runMillis) {
    }

    private record Inputs(SimulationPool pool, String poolSource, Map<Priority, Duration> resolutionTargets) {
    }

    private final AssignmentStrategyRegistry strategyRegistry;
    private final TechnicianRepository technicianRepository;
    private final SupportTeamRepository supportTeamRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final TransactionOperations transactionOperations;
    private final RoutingPolicy routingPolicy = new RoutingPolicy();

    public StrategyComparisonService(
            AssignmentStrategyRegistry strategyRegistry,
            TechnicianRepository technicianRepository,
            SupportTeamRepository supportTeamRepository,
            SlaPolicyRepository slaPolicyRepository,
            TransactionOperations transactionOperations) {
        this.strategyRegistry = Objects.requireNonNull(strategyRegistry, "strategyRegistry cannot be null");
        this.technicianRepository = Objects.requireNonNull(technicianRepository, "technicianRepository cannot be null");
        this.supportTeamRepository = Objects.requireNonNull(supportTeamRepository, "supportTeamRepository cannot be null");
        this.slaPolicyRepository = Objects.requireNonNull(slaPolicyRepository, "slaPolicyRepository cannot be null");
        this.transactionOperations = Objects.requireNonNull(transactionOperations, "transactionOperations cannot be null");
    }

    /**
     * Compares the strategies; every argument may be null (the defaults of {@link ComparisonParameters} apply).
     *
     * @throws com.iitsm.domain.exception.ValidationException (HTTP 400) for a value out of range or an unknown strategy
     */
    public ComparisonRun compare(Long seed, Integer ticketCount, Integer replications, Double arrivalsPerHour,
            List<String> strategyNames) {
        List<AssignmentStrategy> registered = strategyRegistry.getAllStrategies();
        ComparisonParameters parameters = ComparisonParameters.of(seed, ticketCount, replications, arrivalsPerHour,
                strategyNames, registered.stream().map(AssignmentStrategy::getStrategyName).toList());
        Inputs inputs = transactionOperations.execute(status -> readInputs());
        List<AssignmentStrategy> chosen = registered.stream()
                .filter(s -> parameters.strategyNames().contains(s.getStrategyName().toUpperCase(Locale.ROOT))).toList();

        long started = System.nanoTime();
        ComparisonReport report = new StrategyComparison(WorkTimeModel.defaults())
                .run(parameters, inputs.pool(), chosen, inputs.resolutionTargets());
        return new ComparisonRun(report, inputs.poolSource(), (System.nanoTime() - started) / NANOS_PER_MILLI);
    }

    private Inputs readInputs() {
        // Sorted by id: the database returns the roster in no promised order, and the order breaks ties between
        // equal candidates, so an unsorted roster would make "the same seed gives the same result" depend on the database.
        List<TechnicianProfile> profiles = technicianRepository.findAll().stream().map(TechnicianProfile::of)
                .sorted(Comparator.comparing(TechnicianProfile::id)).toList();
        SimulationPool pool = SimulationPool.fromRoster(profiles, owningTeams()).orElse(null);
        String source = pool != null ? POOL_ROSTER : POOL_SYNTHETIC;
        return new Inputs(pool != null ? pool : SimulationPool.syntheticDefault(), source, resolutionTargets());
    }

    /** The team that owns each category, by the live rule (ADR-036): the team that handles it, smallest id first. */
    private Map<IncidentCategory, String> owningTeams() {
        Map<IncidentCategory, String> owners = new EnumMap<>(IncidentCategory.class);
        for (IncidentCategory category : IncidentCategory.values()) {
            List<SupportTeam> teams = supportTeamRepository.findByHandledCategory(category);
            routingPolicy.owningTeamId(category, null, teams).ifPresent(team -> owners.put(category, team));
        }
        return owners;
    }

    private Map<Priority, Duration> resolutionTargets() {
        Map<Priority, Duration> targets = new EnumMap<>(Priority.class);
        for (Priority priority : Priority.values()) {
            targets.put(priority, slaPolicyRepository.findDefaultByPriority(priority)
                    .orElseGet(() -> SlaPolicy.defaultFor(priority)).getResolutionTarget());
        }
        return targets;
    }
}
