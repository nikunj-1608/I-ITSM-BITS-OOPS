package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.SlaPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Remaining-defects plan, Phase H (ADR-037, D20): which stored actor ids a staff view needs names for. */
class IncidentActorsTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    private static Incident classified() {
        Incident incident = new Incident("INC-ACT-01", "Payment gateway timeouts", "Card payments are timing out for all customers.", "EMP-01", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P2, 0.65, List.of(), List.of(), NOW, null, null);
        SlaPolicy policy = new SlaPolicy("SLA-P2", Priority.P2, null, Duration.ofMinutes(15), Duration.ofHours(2), 0.75);
        incident.classify(IncidentCategory.APPLICATION, "SVC-PAYMENT", decision, policy, NOW);
        return incident;
    }

    private static AuditRecord audit(String actor) {
        return new AuditRecord("A-" + actor, NOW, actor, "IncidentCreated", "INC-ACT-01", null, null, "x");
    }

    @Test
    @DisplayName("the ids are the override, the resolver and the audit actors")
    void collectsEveryActor() {
        Incident incident = classified();
        incident.overridePriority(Priority.P1, "ADMIN-1", "Board escalation",
                new SlaPolicy("SLA-P1", Priority.P1, null, Duration.ofMinutes(5), Duration.ofHours(1), 0.75), NOW);
        incident.assign("TECH-1", NOW);
        incident.acknowledge("TECH-1", NOW);
        incident.startInvestigation("TECH-1", NOW);
        incident.resolve(new Resolution("Bug", "Fixed", "None", "APPLICATION", "TECH-2", NOW), NOW);

        assertThat(IncidentActors.of(incident, List.of(audit("EMP-09"))))
                .containsExactlyInAnyOrder("ADMIN-1", "TECH-2", "EMP-09");
    }

    @Test
    @DisplayName("a plain ticket with no trail has no actors, and a missing trail is accepted")
    void plainTicketHasNone() {
        assertThat(IncidentActors.of(classified(), null)).isEmpty();
        assertThat(IncidentActors.of(classified(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("an actor named twice appears once; several tickets are combined for one lookup")
    void combinesTickets() {
        Incident one = classified();
        Incident two = classified();

        assertThat(IncidentActors.ofAll(List.of(one, two), ticket -> List.of(audit("ADMIN-1"), audit("TECH-1"))))
                .containsExactlyInAnyOrder("ADMIN-1", "TECH-1");
    }
}
