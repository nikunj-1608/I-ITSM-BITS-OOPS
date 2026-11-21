package com.iitsm.domain.simulation.assignment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * OOP: Immutable value object. The outcome of replaying one stream through one strategy (ADR-044, Phase L3): every
 * ticket's fate, per technician the number of tickets handled, the busy minutes and the highest number of tickets
 * worked on at the same time (never above the capacity), the longest the waiting queue ever was, and when the last
 * ticket was resolved.
 */
public record ReplayResult(
        String strategyName,
        List<ReplayedTicket> tickets,
        Map<String, Integer> ticketsHandled,
        Map<String, Double> busyMinutes,
        Map<String, Integer> peakConcurrent,
        int maxQueueLength,
        Instant finishedAt) {

    public ReplayResult {
        tickets = List.copyOf(tickets);
        ticketsHandled = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(ticketsHandled));
        busyMinutes = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(busyMinutes));
        peakConcurrent = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(peakConcurrent));
    }
}
