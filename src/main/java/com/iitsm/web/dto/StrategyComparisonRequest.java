package com.iitsm.web.dto;

import java.util.List;

/**
 * Request of the offline strategy comparison (ADR-044, Phase L3). Every field is optional; a missing field takes its
 * default (seed 42, 500 tickets, 10 replications, 15 tickets per hour, every registered strategy). The bounds are
 * checked by the domain ({@code ComparisonParameters}), which names the field in the 400 answer.
 *
 * @param seed            seed of the random generator; the same seed gives the same result
 * @param ticketCount     tickets per replication, 1 to 5000
 * @param replications    independent replications, 1 to 50 (ticketCount x replications at most 25,000)
 * @param arrivalsPerHour mean arrival rate, 0.1 to 1000 tickets per hour
 * @param strategies      names of the strategies to compare; missing or empty means all
 */
public record StrategyComparisonRequest(
        Long seed,
        Integer ticketCount,
        Integer replications,
        Double arrivalsPerHour,
        List<String> strategies) {
}
