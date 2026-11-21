package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * OOP: Value object holding the work-time assumptions of the offline comparison (ADR-044, Phase L3). The service time
 * of a ticket for one technician is
 *
 * <pre>base(priority) x category factor x skill factor x experience factor x concurrency factor x ticket noise</pre>
 *
 * <ul>
 *   <li><b>base</b>: minutes of work for the priority ({@link #BASE_WORK_MINUTES});</li>
 *   <li><b>category factor</b>: some categories take longer ({@link #CATEGORY_FACTOR});</li>
 *   <li><b>skill factor</b>: 1 for a technician with a skill of the category, {@value #UNSKILLED_SLOWDOWN} without;</li>
 *   <li><b>experience factor</b>: from {@value #EXPERIENCE_FACTOR_AT_ZERO} at 0 years down to
 *       {@value #EXPERIENCE_FACTOR_FLOOR} at {@value #EXPERIENCE_FULL_YEARS} years or more, linearly;</li>
 *   <li><b>concurrency factor</b>: {@code 1 + penalty x (tickets already in service / capacity)}: a technician works on
 *       several tickets at once (up to the capacity, the decision of 2026-11-08), each ticket is slower the busier the
 *       technician already is when it starts; a penalty of 0 gives independent parallel work;</li>
 *   <li><b>noise</b>: drawn once per ticket from a log-normal distribution with mean 1
 *       (sigma {@value #NOISE_SIGMA}), so every strategy meets the same luck.</li>
 * </ul>
 *
 * <p>Since Phase L4 an optional <b>familiarity</b> assumption multiplies the time by
 * {@code 1 - speedup x min(1, tickets of this IT service the technician has resolved / fullTickets)}: a technician who
 * has fixed this service's problems before is faster at the next one (off by default, so the L3 results do not change).
 *
 * <p>The service time is fixed when the ticket starts and does not change when the technician's other tickets end.
 * These are modelling assumptions, not measurements; they are returned with every comparison result.
 */
public final class WorkTimeModel {

    /** Minutes of work for a ticket of the priority, before the factors. */
    public static final Map<Priority, Integer> BASE_WORK_MINUTES = priorityMap();
    /** Some categories need more work than others. */
    public static final Map<IncidentCategory, Double> CATEGORY_FACTOR = categoryMap();
    /** Slowdown for a technician without a skill of the ticket's category. */
    public static final double UNSKILLED_SLOWDOWN = 1.6;
    /** Experience factor of a technician with no experience. */
    public static final double EXPERIENCE_FACTOR_AT_ZERO = 1.25;
    /** Experience factor of a technician with full experience. */
    public static final double EXPERIENCE_FACTOR_FLOOR = 0.80;
    /** Years of experience from which the experience factor stays at its floor. */
    public static final int EXPERIENCE_FULL_YEARS = 12;
    /** Resolved tickets of a service that give the full familiarity speed-up (when the assumption is on). */
    public static final int DEFAULT_FAMILIARITY_FULL_TICKETS = 5;
    /** Default concurrency penalty: a technician at full load needs 30 % longer for a ticket. */
    public static final double DEFAULT_CONCURRENCY_PENALTY = 0.30;
    /** Sigma of the log-normal noise. */
    public static final double NOISE_SIGMA = 0.35;

    private static final double MILLIS_PER_MINUTE = 60_000.0;

    private final double concurrencyPenalty;
    private final double familiaritySpeedup;
    private final int familiarityFullTickets;

    private WorkTimeModel(double concurrencyPenalty, double familiaritySpeedup, int familiarityFullTickets) {
        if (!(concurrencyPenalty >= 0.0) || !Double.isFinite(concurrencyPenalty)) {
            throw new IllegalArgumentException("concurrencyPenalty must be a non-negative finite number");
        }
        if (!(familiaritySpeedup >= 0.0) || !(familiaritySpeedup < 1.0)) {
            throw new IllegalArgumentException("familiaritySpeedup must be at least 0 and below 1");
        }
        if (familiarityFullTickets < 1) {
            throw new IllegalArgumentException("familiarityFullTickets must be at least 1");
        }
        this.concurrencyPenalty = concurrencyPenalty;
        this.familiaritySpeedup = familiaritySpeedup;
        this.familiarityFullTickets = familiarityFullTickets;
    }

    /** The default assumptions. */
    public static WorkTimeModel defaults() {
        return new WorkTimeModel(DEFAULT_CONCURRENCY_PENALTY, 0.0, DEFAULT_FAMILIARITY_FULL_TICKETS);
    }

    /** The defaults with another concurrency penalty (0 = independent parallel work). */
    public static WorkTimeModel withConcurrencyPenalty(double penalty) {
        return new WorkTimeModel(penalty, 0.0, DEFAULT_FAMILIARITY_FULL_TICKETS);
    }

    /**
     * The defaults plus service familiarity: a technician is up to {@code speedup} (0 up to 1, exclusive) faster at a
     * ticket whose IT service they have resolved {@code fullTickets} or more tickets of.
     */
    public static WorkTimeModel withServiceFamiliarity(double speedup, int fullTickets) {
        return new WorkTimeModel(DEFAULT_CONCURRENCY_PENALTY, speedup, fullTickets);
    }

    /** Whether the model has the familiarity assumption (and so whether experience with a service is tracked). */
    public boolean hasServiceFamiliarity() {
        return familiaritySpeedup > 0.0;
    }

    public double familiaritySpeedup() {
        return familiaritySpeedup;
    }

    public int familiarityFullTickets() {
        return familiarityFullTickets;
    }

    public double concurrencyPenalty() {
        return concurrencyPenalty;
    }

    /** How long {@code technician} needs for the ticket when it already works on {@code ticketsAlreadyInService}. */
    public Duration serviceTime(SyntheticTicket ticket, TechnicianProfile technician, int ticketsAlreadyInService) {
        return serviceTime(ticket, technician, ticketsAlreadyInService, 0);
    }

    /**
     * As {@link #serviceTime(SyntheticTicket, TechnicianProfile, int)}, for a technician who has already resolved
     * {@code serviceTicketsResolved} tickets of the ticket's IT service (used only when the model has familiarity).
     */
    public Duration serviceTime(SyntheticTicket ticket, TechnicianProfile technician, int ticketsAlreadyInService,
            int serviceTicketsResolved) {
        Objects.requireNonNull(ticket, "ticket cannot be null");
        Objects.requireNonNull(technician, "technician cannot be null");
        if (ticketsAlreadyInService < 0 || ticketsAlreadyInService >= technician.maxCapacity()) {
            throw new IllegalArgumentException("a technician can only start a ticket with a free slot");
        }
        double minutes = BASE_WORK_MINUTES.get(ticket.priority()) * CATEGORY_FACTOR.get(ticket.category())
                * skillFactor(ticket, technician) * experienceFactor(technician.experienceYears())
                * (1.0 + concurrencyPenalty * ticketsAlreadyInService / technician.maxCapacity())
                * familiarityFactor(serviceTicketsResolved)
                * ticket.noise();
        return Duration.ofMillis(Math.max(1L, Math.round(minutes * MILLIS_PER_MINUTE)));
    }

    /** The noise factor of one ticket: log-normal with mean 1, from the stream's own generator. */
    public double drawNoise(RandomGenerator random) {
        return Math.exp(NOISE_SIGMA * random.nextGaussian() - NOISE_SIGMA * NOISE_SIGMA / 2.0);
    }

    /** 1 without the familiarity assumption; otherwise down to {@code 1 - speedup} at {@code fullTickets} resolved. */
    double familiarityFactor(int serviceTicketsResolved) {
        if (familiaritySpeedup == 0.0) {
            return 1.0;
        }
        return 1.0 - familiaritySpeedup * Math.min(1.0, Math.max(0, serviceTicketsResolved) / (double) familiarityFullTickets);
    }

    /** 1 with the category's skill, {@link #UNSKILLED_SLOWDOWN} without. */
    static double skillFactor(SyntheticTicket ticket, TechnicianProfile technician) {
        return technician.hasCategorySkill(ticket.category()) ? 1.0 : UNSKILLED_SLOWDOWN;
    }

    /** Linear from {@link #EXPERIENCE_FACTOR_AT_ZERO} to {@link #EXPERIENCE_FACTOR_FLOOR}. */
    static double experienceFactor(int years) {
        double share = Math.min(1.0, years / (double) EXPERIENCE_FULL_YEARS);
        return EXPERIENCE_FACTOR_AT_ZERO - (EXPERIENCE_FACTOR_AT_ZERO - EXPERIENCE_FACTOR_FLOOR) * share;
    }

    /** The assumptions in words, returned with every comparison. */
    public List<String> assumptions() {
        List<String> all = new ArrayList<>(List.of(
                "Service time = base minutes for the priority (P1 " + BASE_WORK_MINUTES.get(Priority.P1) + ", P2 "
                        + BASE_WORK_MINUTES.get(Priority.P2) + ", P3 " + BASE_WORK_MINUTES.get(Priority.P3) + ", P4 "
                        + BASE_WORK_MINUTES.get(Priority.P4) + ") x category factor (0.8 to 1.3) x skill factor (1, or "
                        + UNSKILLED_SLOWDOWN + " without a skill of the category) x experience factor (from "
                        + EXPERIENCE_FACTOR_AT_ZERO + " at 0 years to " + EXPERIENCE_FACTOR_FLOOR + " from "
                        + EXPERIENCE_FULL_YEARS + " years) x concurrency factor x ticket noise.",
                "A technician works on several tickets at once, up to the capacity; a ticket is slower by "
                        + Math.round(concurrencyPenalty * 100) + " % x (tickets already in service / capacity) when it "
                        + "starts, and its service time does not change afterwards.",
                "Ticket noise is log-normal with mean 1 and sigma " + NOISE_SIGMA + ", drawn once per ticket, so every"
                        + " strategy meets the same stream with the same luck (common random numbers).",
                "Candidates follow the live routing rule: the owning team's technicians with free capacity, then"
                        + " skilled technicians of other teams; a ticket with no candidate waits in a queue, most urgent"
                        + " first, until a slot frees. Every ticket is eventually served.",
                "Resolution time runs from the ticket's arrival to the end of its service, waiting included; the SLA"
                        + " breach rate uses the resolution target of the ticket's priority.",
                "Technicians start empty; the work-time numbers are assumptions of a model, not measurements of the real"
                        + " help desk, so only the comparison between strategies is meaningful."));
        if (hasServiceFamiliarity()) {
            all.add("Familiarity: a technician is up to " + Math.round(familiaritySpeedup * 100) + " % faster at a ticket whose"
                    + " IT service they have resolved " + familiarityFullTickets + " or more tickets of (linear below that).");
        }
        return List.copyOf(all);
    }

    private static Map<Priority, Integer> priorityMap() {
        Map<Priority, Integer> map = new EnumMap<>(Priority.class);
        map.put(Priority.P1, 40);
        map.put(Priority.P2, 75);
        map.put(Priority.P3, 150);
        map.put(Priority.P4, 240);
        return java.util.Collections.unmodifiableMap(map);
    }

    private static Map<IncidentCategory, Double> categoryMap() {
        Map<IncidentCategory, Double> map = new EnumMap<>(IncidentCategory.class);
        map.put(IncidentCategory.HARDWARE, 1.0);
        map.put(IncidentCategory.SOFTWARE, 0.9);
        map.put(IncidentCategory.NETWORK, 1.1);
        map.put(IncidentCategory.DATABASE, 1.25);
        map.put(IncidentCategory.CLOUD, 1.2);
        map.put(IncidentCategory.SECURITY, 1.3);
        map.put(IncidentCategory.AUTHENTICATION, 0.8);
        map.put(IncidentCategory.APPLICATION, 1.0);
        map.put(IncidentCategory.STORAGE, 1.15);
        return java.util.Collections.unmodifiableMap(map);
    }
}
