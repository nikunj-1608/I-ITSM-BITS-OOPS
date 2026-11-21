package com.iitsm.domain.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.SlaTracker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H5: a rejected fix must put the SLA clock back to work.
 *
 * <p>Drives the real lifecycle (resolve, reject, request info, resolve again) through the
 * {@link Incident} aggregate and checks the {@link SlaTracker} afterwards. P1 policy: 5 minute
 * response, 30 minute resolution. Deadline rule under test (ADR-019, option A): the original
 * resolution deadline stays, extended by the time the ticket waited in RESOLVED for the verdict.
 */
class ReopenedTicketSlaTest {

    private Instant t0;
    private Resolution resolution;

    @BeforeEach
    void setUp() {
        t0 = Instant.parse("2026-11-03T10:00:00Z");
        resolution = new Resolution("Root cause", "Steps taken", "Prevention", "SOFTWARE", "TECH-1", t0);
    }

    private Instant at(long minutes) {
        return t0.plus(Duration.ofMinutes(minutes));
    }

    /** A P1 ticket that was acknowledged at +1 min and is IN_PROGRESS from +2 min. */
    private Incident inProgressTicket() {
        Incident incident = new Incident(
                "FIN-2026-000001", "Payment gateway down", "Customers cannot pay, gateway returns errors.",
                "EMP-1", t0);
        PriorityDecision decision = new PriorityDecision(Priority.P1, 0.9, List.of(), List.of(), t0, null, null);
        incident.classify(IncidentCategory.APPLICATION, "SVC-APP", decision, SlaPolicy.defaultFor(Priority.P1), t0);
        incident.assign("TECH-1", t0);
        incident.acknowledge("TECH-1", at(1));
        incident.startInvestigation("TECH-1", at(2));
        return incident;
    }

    private void resolveAndReject(Incident incident, long resolveAt, long rejectAt) {
        incident.resolve(resolution, at(resolveAt));
        incident.reject("EMP-1", "Still failing", at(rejectAt));
    }

    @Test
    @DisplayName("H5: reject clears the first resolution instead of leaving the tracker at MET")
    void rejectClearsResolution() {
        Incident incident = inProgressTicket();
        incident.resolve(resolution, at(10));
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.MET);

        incident.reject("EMP-1", "Still failing", at(12));

        SlaTracker tracker = incident.getSlaTracker();
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
        assertThat(tracker.getResolvedAt()).isNull();
        assertThat(tracker.getStatus()).isNotEqualTo(SlaStatus.MET);
    }

    @Test
    @DisplayName("H5: a rejection after a BREACHED first resolution also clears resolvedAt")
    void rejectAfterBreachedResolutionClearsResolution() {
        Incident incident = inProgressTicket();
        incident.resolve(resolution, at(40)); // 30 minute target missed
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);

        incident.reject("EMP-1", "Still failing", at(41));

        assertThat(incident.getSlaTracker().getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("H5: the first response stays on record after a reopen")
    void rejectKeepsFirstResponse() {
        Incident incident = inProgressTicket();
        resolveAndReject(incident, 10, 12);

        assertThat(incident.getSlaTracker().getRespondedAt()).isEqualTo(at(1));
    }

    @Test
    @DisplayName("H5: a reopened ticket that runs past its deadline becomes BREACHED")
    void reopenedTicketBreachesWhenDeadlinePasses() {
        Incident incident = inProgressTicket();
        resolveAndReject(incident, 10, 12);

        incident.updateSlaStatus(at(60));

        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("H5: a reopened ticket warns again on the way to its deadline (AT_RISK, then WARNING)")
    void reopenedTicketPassesThroughWarningStates() {
        Incident incident = inProgressTicket();
        resolveAndReject(incident, 10, 12); // waited 2 min in RESOLVED -> deadline 10:32

        incident.updateSlaStatus(at(25)); // 25 of 32 minutes = 78%
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.AT_RISK);

        incident.updateSlaStatus(at(30)); // 30 of 32 minutes = 94%
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.WARNING);
    }

    @Test
    @DisplayName("H5: time spent waiting for the employee's verdict is credited to the deadline")
    void verdictWaitIsCreditedToDeadline() {
        Incident incident = inProgressTicket();
        Instant originalDeadline = incident.getSlaTracker().getResolutionDueAt();

        resolveAndReject(incident, 10, 14); // 4 minutes in RESOLVED

        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(originalDeadline.plus(Duration.ofMinutes(4)));
    }

    @Test
    @DisplayName("H5: an info request on a reopened ticket pauses the clock and shifts the deadline")
    void infoRequestAfterReopenPausesAndShiftsDeadline() {
        Incident incident = inProgressTicket();
        resolveAndReject(incident, 10, 12); // deadline 10:32
        Instant deadlineBeforePause = incident.getSlaTracker().getResolutionDueAt();

        incident.requestInfo("TECH-1", "Need the new error log", at(15));
        assertThat(incident.getSlaTracker().isPaused()).isTrue();

        incident.provideInfo("EMP-1", "Log attached", at(25));

        SlaTracker tracker = incident.getSlaTracker();
        assertThat(tracker.isPaused()).isFalse();
        assertThat(tracker.getPausedDuration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(tracker.getResolutionDueAt()).isEqualTo(deadlineBeforePause.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("H5: resolving again in time is MET against the extended deadline")
    void secondResolutionInTimeIsMet() {
        Incident incident = inProgressTicket();
        resolveAndReject(incident, 10, 12); // deadline 10:32

        incident.resolve(resolution, at(20));

        assertThat(incident.getSlaTracker().getResolvedAt()).isEqualTo(at(20));
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.MET);
    }

    @Test
    @DisplayName("H5: resolving again after the deadline is BREACHED")
    void secondResolutionTooLateIsBreached() {
        Incident incident = inProgressTicket();
        resolveAndReject(incident, 10, 12); // deadline 10:32

        incident.resolve(resolution, at(45));

        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("H5: three reject loops in a row leave the ticket unresolved and monitorable")
    void repeatedRejectLoopsStayMonitored() {
        Incident incident = inProgressTicket();
        resolveAndReject(incident, 10, 11);
        resolveAndReject(incident, 12, 13);
        resolveAndReject(incident, 14, 15);

        assertThat(incident.getReopenCount()).isEqualTo(3);
        assertThat(incident.getSlaTracker().getResolvedAt()).isNull();

        incident.updateSlaStatus(at(120));

        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("H5: a ticket that is verified (not rejected) keeps its MET result")
    void verifiedTicketKeepsMet() {
        Incident incident = inProgressTicket();
        incident.resolve(resolution, at(10));

        incident.verify("EMP-1", at(12));

        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.MET);
        assertThat(incident.getSlaTracker().getResolvedAt()).isEqualTo(at(10));
    }
}
