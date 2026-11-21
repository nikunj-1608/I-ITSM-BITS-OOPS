package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.DisplayName;

/**
 * Remaining-defects plan, Phase G (ADR-036): which statuses a reclassify into another team may move. The rule lives on
 * the status, not in the service that asks.
 */
class IncidentStatusReroutingTest {

    @ParameterizedTest(name = "{0} can be rerouted")
    @EnumSource(value = IncidentStatus.class, names = {"ASSIGNED", "ACKNOWLEDGED", "IN_PROGRESS"})
    @DisplayName("A ticket a technician is working on can be routed to another team")
    void workingStatesCanBeRerouted(IncidentStatus status) {
        assertThat(status.canBeRerouted()).isTrue();
    }

    @ParameterizedTest(name = "{0} cannot be rerouted")
    @EnumSource(value = IncidentStatus.class, names = {"NEW", "CLASSIFIED", "PENDING_INFO", "RESOLVED", "VERIFIED", "CLOSED"})
    @DisplayName("A waiting, paused or finished ticket is not moved from a technician by a reclassify")
    void otherStatesAreNotRerouted(IncidentStatus status) {
        assertThat(status.canBeRerouted()).isFalse();
    }

    @ParameterizedTest(name = "{0}: a reroutable ticket always holds a technician slot")
    @EnumSource(IncidentStatus.class)
    @DisplayName("Every status that can be rerouted holds a technician slot (the slot is what the reroute releases)")
    void reroutableImpliesSlot(IncidentStatus status) {
        if (status.canBeRerouted()) {
            assertThat(status.holdsTechnicianSlot()).isTrue();
        }
    }
}
