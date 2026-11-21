package com.iitsm.domain.repository;

import com.iitsm.domain.strategy.assignment.AssignmentDecision;
import java.util.List;

/**
 * Pattern: Repository (port). Stores why each ticket got its technician (ADR-044, Phase L2). Decisions are only added,
 * never changed; they are saved in the same transaction as the assignment they explain.
 */
public interface AssignmentDecisionRepository {

    /** Adds a decision. */
    AssignmentDecision save(AssignmentDecision decision);

    /** The newest {@code limit} decisions of the ticket, newest first. */
    List<AssignmentDecision> findNewestByIncidentId(String incidentId, int limit);
}
