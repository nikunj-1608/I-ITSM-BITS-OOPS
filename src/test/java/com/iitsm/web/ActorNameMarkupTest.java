package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase H (ADR-037): the admin ticket dialog shows the person's name with the stored id beside
 * it (override, resolver, audit actor), escapes both, and the static files carry the new asset version.
 */
class ActorNameMarkupTest {

    private static String indexHtml;
    private static String appJs;

    @BeforeAll
    static void loadStaticResources() throws IOException {
        indexHtml = read("static/index.html");
        appJs = read("static/app.js");
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = ActorNameMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String functionBody(String name) {
        int start = appJs.indexOf("function " + name + "(");
        assertThat(start).as("function " + name).isGreaterThanOrEqualTo(0);
        int end = appJs.indexOf("\nfunction ", start + 1);
        return appJs.substring(start, end < 0 ? appJs.length() : end);
    }

    @Test
    @DisplayName("tdActor shows the name and the id, escapes both and falls back to the stored id")
    void helperEscapesAndFallsBack() {
        String helper = functionBody("tdActor");
        assertThat(helper).contains("tdEsc(name)", "tdEsc(id)", "TICKET_DETAIL_EMPTY");
        assertThat(helper).contains("if (!name)");
    }

    @Test
    @DisplayName("the override callout, the resolution and the audit timeline use the names the API sends")
    void dialogUsesTheNames() {
        assertThat(functionBody("tdOverrideInfo")).contains("tdActor(decision.overriddenBy, decision.overriddenByName)");
        assertThat(functionBody("tdResolutionHtml")).contains("tdActor(resolution.resolvedBy, resolution.resolvedByName)");
        assertThat(functionBody("tdAuditItem")).contains("tdActor(record.actorId, record.actorName)");
    }

    @Test
    @DisplayName("the static files carry the Phase H asset version")
    void assetVersion() {
        assertThat(indexHtml).contains("/styles.css?v=5.2").contains("/app.js?v=5.2");
    }
}
