package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract of the technician's team view in the static web app (ADR-032, Phase E). A technician's "team queue" now holds
 * their own tickets and the unassigned tickets of their team, so the button says so instead of promising the whole
 * team; the team lead's tab, which does show the whole team, keeps its own lists.
 */
class TechnicianTeamModeMarkupTest {

    private static String indexHtml;
    private static String appJs;

    @BeforeAll
    static void load() throws IOException {
        indexHtml = read("/static/index.html");
        appJs = read("/static/app.js");
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = TechnicianTeamModeMarkupTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("classpath resource " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("The technician's team button names what it shows: the unassigned tickets of the team")
    void buttonIsLabelledForWhatItShows() {
        int button = indexHtml.indexOf("id=\"btn-tech-mode-team\"");
        assertThat(button).isGreaterThan(0);
        String markup = indexHtml.substring(button, indexHtml.indexOf("</button>", button));

        assertThat(markup).contains("Unassigned Team Tickets").doesNotContain(">👥 Team Queue<");
    }

    @Test
    @DisplayName("Both team views still read the same endpoint, and the team lead tab still renders the whole list")
    void endpointsAreUnchanged() {
        assertThat(appJs).contains("/api/technician/team-queue").contains("renderTeamLeadQueues(teamQueue)");
    }

    @Test
    @DisplayName("The registration form shows the server's message when a registration is refused")
    void registrationShowsTheServerMessage() {
        assertThat(appJs).contains("fetch('/api/auth/register'").contains("err.message || 'Registration failed'");
    }
}
