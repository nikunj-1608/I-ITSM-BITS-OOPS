package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.exception.ValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/**
 * OOP: Immutable value object. The validated parameters of an offline strategy comparison (ADR-044, Phase L3).
 *
 * <p>Every field is bounded so one request stays within a few seconds: at most {@value #MAX_TICKET_COUNT} tickets, at
 * most {@value #MAX_REPLICATIONS} replications and at most {@value #MAX_TICKETS_TIMES_REPLICATIONS} tickets x
 * replications. A bad value is a {@link ValidationException} (HTTP 400, code {@code ERR_VALIDATION_FAILED}, naming the
 * field). The virtual time starts at {@link #DEFAULT_START}; the strategy names are upper case, without duplicates, in
 * alphabetical order so the output order does not depend on the registry's map.
 */
public record ComparisonParameters(
        long seed,
        int ticketCount,
        int replications,
        double arrivalsPerHour,
        List<String> strategyNames,
        Instant startInstant) {

    public static final long DEFAULT_SEED = 42L;
    public static final int DEFAULT_TICKET_COUNT = 500;
    public static final int DEFAULT_REPLICATIONS = 10;
    /**
     * Busy but stable: about 40 % of the seeded roster's capacity overall and about 75 % for its busiest team
     * (Application, which owns 31 % of the category mix), and about 85 % for the synthetic pool's busiest team.
     */
    public static final double DEFAULT_ARRIVALS_PER_HOUR = 15.0;
    public static final int MAX_TICKET_COUNT = 5_000;
    public static final int MAX_REPLICATIONS = 50;
    public static final int MAX_TICKETS_TIMES_REPLICATIONS = 25_000;
    public static final double MIN_ARRIVALS_PER_HOUR = 0.1;
    public static final double MAX_ARRIVALS_PER_HOUR = 1_000.0;
    /** A Monday, 08:00 UTC: the virtual start of every comparison. */
    public static final Instant DEFAULT_START = Instant.parse("2026-01-05T08:00:00Z");

    public ComparisonParameters {
        requireRange("ticketCount", ticketCount, 1, MAX_TICKET_COUNT);
        requireRange("replications", replications, 1, MAX_REPLICATIONS);
        if (!(arrivalsPerHour >= MIN_ARRIVALS_PER_HOUR) || !(arrivalsPerHour <= MAX_ARRIVALS_PER_HOUR)) {
            throw new ValidationException("arrivalsPerHour", "must be a number from " + MIN_ARRIVALS_PER_HOUR + " to "
                    + MAX_ARRIVALS_PER_HOUR + " (tickets per hour)");
        }
        if ((long) ticketCount * replications > MAX_TICKETS_TIMES_REPLICATIONS) {
            throw new ValidationException("ticketCount", "ticketCount x replications must not exceed "
                    + MAX_TICKETS_TIMES_REPLICATIONS + " (got " + (long) ticketCount * replications + ")");
        }
        Objects.requireNonNull(strategyNames, "strategyNames cannot be null");
        if (strategyNames.isEmpty()) {
            throw new ValidationException("strategies", "at least one strategy is needed");
        }
        strategyNames = List.copyOf(strategyNames);
        Objects.requireNonNull(startInstant, "startInstant cannot be null");
    }

    /**
     * Applies the defaults to the fields a request left out and checks the strategy names against the registry's.
     *
     * @param requested the requested strategy names; null or empty means every available strategy
     * @param available the names of the registered strategies
     */
    public static ComparisonParameters of(Long seed, Integer ticketCount, Integer replications, Double arrivalsPerHour,
            List<String> requested, Collection<String> available) {
        TreeSet<String> valid = new TreeSet<>();
        available.forEach(name -> valid.add(name.toUpperCase(Locale.ROOT)));
        TreeSet<String> chosen = new TreeSet<>();
        if (requested == null || requested.isEmpty()) {
            chosen.addAll(valid);
        } else {
            for (String name : requested) {
                String normalized = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
                if (!valid.contains(normalized)) {
                    throw new ValidationException("strategies", "unknown assignment strategy '"
                            + (name == null ? "null" : name.trim()) + "'. Valid strategies: " + String.join(", ", valid));
                }
                chosen.add(normalized);
            }
        }
        return new ComparisonParameters(
                seed != null ? seed : DEFAULT_SEED,
                ticketCount != null ? ticketCount : DEFAULT_TICKET_COUNT,
                replications != null ? replications : DEFAULT_REPLICATIONS,
                arrivalsPerHour != null ? arrivalsPerHour : DEFAULT_ARRIVALS_PER_HOUR,
                new ArrayList<>(chosen),
                DEFAULT_START);
    }

    private static void requireRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new ValidationException(field, "must be a whole number from " + min + " to " + max);
        }
    }
}
