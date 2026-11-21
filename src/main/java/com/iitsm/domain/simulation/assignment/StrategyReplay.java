package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentUrgencyOrdering;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.strategy.assignment.AssignmentRecommendation;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.CandidateFilter;
import com.iitsm.domain.strategy.assignment.RoutedCandidates;
import com.iitsm.domain.strategy.assignment.ServiceExperienceBindable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Pattern: Discrete-event simulation. Replays one ticket stream through one assignment strategy, entirely in memory
 * (ADR-044, Phase L3): no repository, no event publisher, no notification, no live clock, no live strategy state. The
 * strategy is asked through its public contract ({@link AssignmentStrategy#recommend}), over the candidates the live
 * routing rule gives ({@link CandidateFilter#route}), so the replay measures what production would do.
 *
 * <p>Two {@link PriorityQueue}s drive it: the event queue (arrivals and completions, in virtual time; at the same
 * instant a completion comes before an arrival, so a slot that frees is available to the ticket arriving then) and the
 * waiting queues (one per category, most urgent first by {@link IncidentUrgencyOrdering}; tickets of one category all
 * see the same candidate pool, so the next ticket to serve is the most urgent among the heads that can be served now,
 * which equals scanning one queue in urgency order). A technician holds up to the capacity tickets at once; each ticket
 * runs for its own service time ({@link WorkTimeModel}). The replay ends when the last ticket is resolved.
 *
 * <p>Since Phase L4, when the model has service familiarity, the replay counts for every technician the tickets of each IT
 * service they have resolved so far; that count speeds up the technician's next ticket of the service and is what a
 * {@link ServiceExperienceBindable} strategy (the weighted composite with the service-experience factor) reads: the
 * strategy is re-bound to this in-memory count, so it never reads the real database during a simulation. Without the
 * familiarity assumption the strategy sees no experience at all and ranks as it always did.
 */
public final class StrategyReplay {

    private static final int COMPLETION = 0;
    private static final int ARRIVAL = 1;
    private static final double MILLIS_PER_MINUTE = 60_000.0;

    private final SimulationPool pool;
    private final WorkTimeModel model;
    private final CandidateFilter candidateFilter = new CandidateFilter();

    public StrategyReplay(SimulationPool pool, WorkTimeModel model) {
        this.pool = Objects.requireNonNull(pool, "pool cannot be null");
        this.model = Objects.requireNonNull(model, "model cannot be null");
    }

    /**
     * Replays the stream; {@code strategy} should be a {@link AssignmentStrategy#freshInstance() fresh instance}, because
     * a strategy with state (round robin) advances it.
     */
    public ReplayResult run(AssignmentStrategy strategy, List<PreparedTicket> tickets, Instant start) {
        Objects.requireNonNull(strategy, "strategy cannot be null");
        Objects.requireNonNull(start, "start cannot be null");
        if (tickets.isEmpty()) {
            throw new IllegalArgumentException("a replay needs at least one ticket");
        }
        for (int i = 0; i < tickets.size(); i++) {
            if (tickets.get(i).spec().index() != i) {
                throw new IllegalArgumentException("ticket " + i + " must carry index " + i + " (arrival order)");
            }
        }
        return new Run(strategy, tickets, start).execute();
    }

    private record Event(Instant time, int order, long sequence, int ticketIndex, String technicianId) {
    }

    /** The state of one replay. */
    private final class Run {
        private final AssignmentStrategy strategy;
        private final AssignmentStrategy effectiveStrategy;
        private final Map<String, Map<String, Integer>> resolvedByService = new HashMap<>();
        private final List<PreparedTicket> tickets;
        private final Instant start;
        private final PriorityQueue<Event> events = new PriorityQueue<>(Comparator.comparing(Event::time)
                .thenComparingInt(Event::order).thenComparingLong(Event::sequence));
        private final Map<IncidentCategory, PriorityQueue<PreparedTicket>> waiting = new EnumMap<>(IncidentCategory.class);
        private final Map<String, TechnicianProfile> profiles = new LinkedHashMap<>();
        private final Map<String, Technician> technicians = new LinkedHashMap<>();
        private final List<Technician> roster = new ArrayList<>();
        private final Map<String, Integer> handled = new LinkedHashMap<>();
        private final Map<String, Double> busy = new LinkedHashMap<>();
        private final Map<String, Integer> peak = new LinkedHashMap<>();
        private final ReplayedTicket[] outcomes;
        private long sequence;
        private int waitingCount;
        private int maxQueue;
        private Instant now;

        Run(AssignmentStrategy strategy, List<PreparedTicket> tickets, Instant start) {
            this.strategy = strategy;
            this.tickets = tickets;
            this.start = start;
            this.outcomes = new ReplayedTicket[tickets.size()];
            for (TechnicianProfile profile : pool.technicians()) {
                Technician technician = profile.toTechnician();
                profiles.put(profile.id(), profile);
                technicians.put(profile.id(), technician);
                roster.add(technician);
                handled.put(profile.id(), 0);
                busy.put(profile.id(), 0.0);
                peak.put(profile.id(), 0);
            }
            Comparator<PreparedTicket> urgency = Comparator.comparing(PreparedTicket::incident, IncidentUrgencyOrdering.INSTANCE)
                    .thenComparingInt(t -> t.spec().index());
            for (IncidentCategory category : IncidentCategory.values()) {
                waiting.put(category, new PriorityQueue<>(urgency));
            }
            this.effectiveStrategy = strategy instanceof ServiceExperienceBindable bindable
                    ? bindable.bindExperience(this::experienceOf) : strategy;
        }

        /** The service-experience port of this replay: the tickets resolved so far, or nothing without familiarity. */
        private Map<String, Integer> experienceOf(String serviceId, Collection<String> technicianIds) {
            Map<String, Integer> counts = new HashMap<>();
            if (model.hasServiceFamiliarity()) {
                for (String id : technicianIds) {
                    counts.put(id, resolvedOf(id, serviceId));
                }
            }
            return counts;
        }

        private int resolvedOf(String technicianId, String serviceId) {
            return resolvedByService.getOrDefault(technicianId, Map.of()).getOrDefault(serviceId, 0);
        }

        ReplayResult execute() {
            for (PreparedTicket ticket : tickets) {
                events.add(new Event(start.plus(ticket.spec().arrivalOffset()), ARRIVAL, sequence++, ticket.spec().index(), null));
            }
            Instant last = start;
            while (!events.isEmpty()) {
                Event event = events.poll();
                now = event.time();
                last = now;
                if (event.order() == ARRIVAL) {
                    onArrival(tickets.get(event.ticketIndex()));
                } else {
                    onCompletion(event);
                }
            }
            if (waitingCount > 0) {
                throw new IllegalStateException(waitingCount + " ticket(s) can never be served by this pool");
            }
            return new ReplayResult(strategy.getStrategyName(), List.of(outcomes), handled, busy, peak, maxQueue, last);
        }

        private void onArrival(PreparedTicket ticket) {
            RoutedCandidates routed = route(ticket);
            if (routed.isEmpty()) {
                waiting.get(ticket.spec().category()).add(ticket);
                waitingCount++;
                maxQueue = Math.max(maxQueue, waitingCount);
            } else {
                assign(ticket, routed);
            }
        }

        private void onCompletion(Event event) {
            PreparedTicket done = tickets.get(event.ticketIndex());
            technicians.get(event.technicianId()).releaseIncident(done.incident().getId());
            if (model.hasServiceFamiliarity()) {
                resolvedByService.computeIfAbsent(event.technicianId(), id -> new HashMap<>())
                        .merge(done.spec().serviceId(), 1, Integer::sum);
            }
            drainWaiting();
        }

        /** Serves waiting tickets, most urgent first among the categories that can be served, while slots are free. */
        private void drainWaiting() {
            while (waitingCount > 0) {
                PreparedTicket best = null;
                RoutedCandidates bestRoute = null;
                for (PriorityQueue<PreparedTicket> queue : waiting.values()) {
                    PreparedTicket head = queue.peek();
                    if (head == null || (best != null && queue.comparator().compare(head, best) >= 0)) {
                        continue;
                    }
                    RoutedCandidates routed = route(head);
                    if (!routed.isEmpty()) {
                        best = head;
                        bestRoute = routed;
                    }
                }
                if (best == null) {
                    return;
                }
                waiting.get(best.spec().category()).poll();
                waitingCount--;
                assign(best, bestRoute);
            }
        }

        private RoutedCandidates route(PreparedTicket ticket) {
            IncidentCategory category = ticket.spec().category();
            return candidateFilter.route(roster, pool.owningTeamOf(category), category, Set.of());
        }

        private void assign(PreparedTicket ticket, RoutedCandidates routed) {
            AssignmentRecommendation recommendation = effectiveStrategy.recommend(ticket.incident(), routed.technicians());
            Technician chosen = recommendation.technician();
            if (!routed.technicians().contains(chosen)) {
                throw new IllegalStateException(strategy.getStrategyName() + " chose a technician that was not offered");
            }
            chosen.assignIncident(ticket.incident().getId());
            TechnicianProfile profile = profiles.get(chosen.getId());
            int inService = chosen.getActiveIncidentCount() - 1;
            int resolvedBefore = model.hasServiceFamiliarity() ? resolvedOf(chosen.getId(), ticket.spec().serviceId()) : 0;
            Duration service = model.serviceTime(ticket.spec(), profile, inService, resolvedBefore);
            Instant completes = now.plus(service);
            events.add(new Event(completes, COMPLETION, sequence++, ticket.spec().index(), chosen.getId()));
            outcomes[ticket.spec().index()] = new ReplayedTicket(ticket.spec().index(), ticket.spec().priority(),
                    ticket.spec().category(), start.plus(ticket.spec().arrivalOffset()), now, completes, chosen.getId(),
                    !profile.hasCategorySkill(ticket.spec().category()), service);
            handled.merge(chosen.getId(), 1, Integer::sum);
            busy.merge(chosen.getId(), service.toMillis() / MILLIS_PER_MINUTE, Double::sum);
            peak.merge(chosen.getId(), chosen.getActiveIncidentCount(), Math::max);
        }
    }
}
