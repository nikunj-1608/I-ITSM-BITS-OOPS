package com.iitsm.web.mapper;

import com.iitsm.domain.strategy.assignment.AssignmentDecision;
import com.iitsm.domain.strategy.assignment.CandidateScore;
import com.iitsm.domain.strategy.assignment.FactorScore;
import com.iitsm.web.dto.AdminIncidentDetailView;
import java.util.List;
import java.util.Map;

/**
 * Pattern: Mapper. Turns assignment decisions into the administrator's "Why this technician" view (ADR-044): names
 * beside ids (from the detail's single batch lookup, ADR-037) and one plain sentence per decision. Used only by
 * {@link IncidentDtoMapper#toAdminDetailView}; no technician or employee view carries decisions.
 */
final class AssignmentDecisionViewMapper {

    private AssignmentDecisionViewMapper() {
    }

    static List<AdminIncidentDetailView.AssignmentDecisionView> toViews(
            List<AssignmentDecision> decisions, Map<String, String> names) {
        return decisions.stream().map(decision -> toView(decision, names)).toList();
    }

    private static AdminIncidentDetailView.AssignmentDecisionView toView(AssignmentDecision d, Map<String, String> names) {
        return new AdminIncidentDetailView.AssignmentDecisionView(
                d.poolKind().name(),
                d.poolKind().getLabel(),
                d.strategyName(),
                d.chosenTechnicianId(),
                names.get(d.chosenTechnicianId()),
                d.actorId(),
                names.get(d.actorId()),
                d.relatedIncidentId(),
                d.decidedAt(),
                summary(d, names),
                d.candidateCount(),
                d.candidates().stream().map(c -> toCandidateView(c, names)).toList());
    }

    private static AdminIncidentDetailView.CandidateView toCandidateView(CandidateScore c, Map<String, String> names) {
        List<AdminIncidentDetailView.FactorView> factors = c.factorScores().stream()
                .map(AssignmentDecisionViewMapper::toFactorView).toList();
        return new AdminIncidentDetailView.CandidateView(
                c.technicianId(), names.get(c.technicianId()), c.rank(), c.chosen(), c.total(), factors);
    }

    private static AdminIncidentDetailView.FactorView toFactorView(FactorScore f) {
        return new AdminIncidentDetailView.FactorView(f.factorKey(), f.rawScore(), f.weight(), f.contribution(), f.detail());
    }

    /** One plain sentence saying how the technician was chosen. */
    static String summary(AssignmentDecision d, Map<String, String> names) {
        String technician = person(d.chosenTechnicianId(), names);
        String actor = person(d.actorId(), names);
        return switch (d.poolKind()) {
            case OWNING_TEAM -> String.format("%s chose %s from %d available technician(s) of the owning team.",
                    d.strategyName(), technician, d.candidateCount());
            case SKILLED_OVERFLOW -> String.format("The owning team had nobody free, so %s chose %s from %d skilled "
                    + "technician(s) of other teams.", d.strategyName(), technician, d.candidateCount());
            case MANUAL -> String.format("%s assigned %s by hand; no strategy was used.", actor, technician);
            case INHERITED_FROM_PARENT -> String.format("This ticket is a duplicate of %s, so it rides that ticket's "
                    + "technician %s without taking a capacity slot.", d.relatedIncidentId(), technician);
            case REQUEUED_TO_SAME_TECHNICIAN -> String.format("%s rejected the wrong-domain request, so the ticket went "
                    + "back to %s.", actor, technician);
        };
    }

    private static String person(String id, Map<String, String> names) {
        String name = names.get(id);
        return name == null ? id : name + " (" + id + ")";
    }
}
