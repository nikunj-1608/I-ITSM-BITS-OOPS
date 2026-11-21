package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TicketDisplayNameTest {

    @Test
    @DisplayName("puts the priority in front of the ticket id with a hyphen and no spaces")
    void prefixesThePriority() {
        assertThat(TicketDisplayName.of(Priority.P2, "FIN-2026-000142")).isEqualTo("P2-FIN-2026-000142");
        assertThat(TicketDisplayName.of(Priority.P1, "ENG-2026-000007")).isEqualTo("P1-ENG-2026-000007");
        assertThat(TicketDisplayName.of(Priority.P4, "MISC-2027-000001")).doesNotContain(" ");
    }

    @Test
    @DisplayName("a ticket without a priority yet is shown by its plain id")
    void noPriorityMeansPlainId() {
        assertThat(TicketDisplayName.of(null, "FIN-2026-000142")).isEqualTo("FIN-2026-000142");
        assertThat(TicketDisplayName.of(Priority.P3, null)).isNull();
    }

    @Test
    @DisplayName("the name changes with the priority but the id inside it does not")
    void followsThePriority() {
        String id = "FIN-2026-000142";
        assertThat(TicketDisplayName.of(Priority.P3, id)).isEqualTo("P3-FIN-2026-000142");
        assertThat(TicketDisplayName.of(Priority.P1, id)).isEqualTo("P1-FIN-2026-000142");
        assertThat(TicketDisplayName.canonicalId(TicketDisplayName.of(Priority.P1, id))).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"P1-FIN-2026-000142", "P2-FIN-2026-000142", "P3-FIN-2026-000142",
            "P4-FIN-2026-000142", "  p2-fin-2026-000142  ", "P2-OPS-2027-1234567", "P2-MISC-2026-000001"})
    @DisplayName("canonicalId reduces a complete display name to the plain ticket id")
    void canonicalIdStripsThePriority(String text) {
        assertThat(TicketDisplayName.canonicalId(text)).doesNotStartWith("P").doesNotStartWith("p")
                .matches("(?i)[A-Z]{2,4}-\\d{4}-\\d{6,}");
    }

    @Test
    @DisplayName("canonicalId keeps the case of an id it did not have to change, and trims it")
    void canonicalIdLeavesPlainIdsAlone() {
        assertThat(TicketDisplayName.canonicalId("FIN-2026-000142")).isEqualTo("FIN-2026-000142");
        assertThat(TicketDisplayName.canonicalId("  FIN-2026-000142 ")).isEqualTo("FIN-2026-000142");
        assertThat(TicketDisplayName.canonicalId(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"P2-FIN", "P2-", "P5-FIN-2026-000142", "P0-FIN-2026-000142", "PX-FIN-2026-000142",
            "P2 FIN-2026-000142", "P2_FIN-2026-000142", "VPN is down", "P2-VPN-down", "P2-P2-FIN-2026-000142"})
    @DisplayName("partial names, other priorities and ordinary words are returned unchanged")
    void canonicalIdIgnoresAnythingElse(String text) {
        assertThat(TicketDisplayName.canonicalId(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("an old-style id with a priority prefix is recognised too")
    void legacyIdsAreRecognised() {
        assertThat(TicketDisplayName.canonicalId("P1-INC-3F9A21BC")).isEqualTo("INC-3F9A21BC");
    }
}
