package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.strategy.classification.ClassificationResult;
import com.iitsm.domain.strategy.classification.CompositeClassifier;
import com.iitsm.domain.strategy.classification.KeywordClassifier;
import com.iitsm.domain.strategy.classification.NaiveBayesClassifier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-050: the rebuilt Employee Portal tab. There is no JavaScript test runner, so the structure is pinned as text;
 * the behaviour is exercised in Microsoft Edge by {@code scripts/live-checks/employee_portal_browser.py}.
 */
class EmployeePortalMarkupTest {

    /** The categories each seeded support team handles (CompanySeeder), keyed by the name a chip shows. */
    private static final Map<String, Set<IncidentCategory>> TEAM_CATEGORIES = Map.of(
            "Network team", Set.of(IncidentCategory.NETWORK),
            "Database & Storage team", Set.of(IncidentCategory.DATABASE, IncidentCategory.STORAGE),
            "Application team", Set.of(IncidentCategory.APPLICATION, IncidentCategory.SOFTWARE),
            "Security team", Set.of(IncidentCategory.SECURITY, IncidentCategory.AUTHENTICATION),
            "Cloud Platform team", Set.of(IncidentCategory.CLOUD),
            "Service Desk", Set.of(IncidentCategory.HARDWARE));

    private static String appJs;
    private static String indexHtml;
    private static String stylesCss;
    private static String employeeHtml;
    private static String employeeJs;

    @BeforeAll
    static void load() throws IOException {
        appJs = read("static/app.js");
        indexHtml = read("static/index.html");
        stylesCss = read("static/styles.css");
        int start = indexHtml.indexOf("<section id=\"console-employee\"");
        int end = indexHtml.indexOf("</section>", start);
        assertThat(start).as("employee section").isGreaterThan(0);
        employeeHtml = indexHtml.substring(start, end);
        int jsStart = appJs.indexOf("// 6. Console 1: Employee Portal Logic");
        int jsEnd = appJs.indexOf("// 7. Console 2", jsStart);
        assertThat(jsStart).as("employee script section").isGreaterThan(0);
        employeeJs = appJs.substring(jsStart, jsEnd);
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = EmployeePortalMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("every element id the employee script looks up exists in index.html")
    void everyLookedUpIdExists() {
        Set<String> ids = new LinkedHashSet<>();
        Matcher literal = Pattern.compile("getElementById\\('([A-Za-z0-9-]+)'\\)").matcher(employeeJs);
        while (literal.find()) {
            ids.add(literal.group(1));
        }
        Matcher viaHelper = Pattern.compile("setText\\('([A-Za-z0-9-]+)'").matcher(employeeJs);
        while (viaHelper.find()) {
            ids.add(viaHelper.group(1));
        }
        Matcher listed = Pattern.compile("\\['(emp-[a-z-]+)', '(emp-[a-z-]+)', '(emp-[a-z-]+)', '(emp-[a-z-]+)'\\]")
                .matcher(employeeJs);
        while (listed.find()) {
            for (int g = 1; g <= 4; g++) {
                ids.add(listed.group(g));
            }
        }
        // updateEmployeeStats builds emp-stat-, emp-bar- and emp-pct- ids from these keys
        assertThat(employeeJs).contains("getElementById(`emp-stat-${key}`)", "getElementById(`emp-bar-${key}`)",
                "getElementById(`emp-pct-${key}`)");
        for (String key : List.of("total", "active", "pending", "resolved")) {
            ids.addAll(List.of("emp-stat-" + key, "emp-bar-" + key, "emp-pct-" + key));
        }
        assertThat(ids).hasSizeGreaterThan(20);
        assertThat(ids).allSatisfy(id -> assertThat(indexHtml).as(id).contains("id=\"" + id + "\""));
    }

    @Test
    @DisplayName("the employee section shows no priority, SLA, gateway, knowledge base, catalog or classifier preview")
    void noStaffOnlyContent() {
        String html = employeeHtml;
        assertThat(html.toLowerCase()).doesNotContain("priority", "sla", "gateway", "knowledge base",
                "service catalog", "tier", "classifier", "emp-ai-preview", "preview-category", "preview-service",
                "sim active", "sec-pol");
        assertThat(html).doesNotContainPattern("\\bP[1-4]\\b");
        assertThat(appJs).doesNotContain("updateAiPreview", "emp-ai-preview", "openEmployeeDetailsModal");
    }

    @Test
    @DisplayName("the privacy banner is not shown on the Employee tab")
    void privacyBannerIsGone() {
        assertThat(employeeHtml).doesNotContain("emp-privacy-banner", "Privacy Protection", "ENFORCED");
        assertThat(employeeHtml).contains("<!-- Sub-navigation");
    }

    @Test
    @DisplayName("the script reads only fields EmployeeIncidentView sends (no priority, SLA, category or createdAt)")
    void readsOnlyEmployeeViewFields() {
        assertThat(employeeJs).doesNotContainPattern(
                "\\.(priority|priorityDecision|slaTracker|slaStatus|category|createdAt|displayId|notes)\\b");
        assertThat(employeeJs).contains("t.createdTime", "t.assigneeName", "t.resolutionText", "t.reopenCount",
                "auditTrail", "allowedActions");
        // A comment explains that PENDING_INFO never reaches this screen; no code may compare a status with it
        assertThat(employeeJs).doesNotContainPattern("[=!]==?\\s*'PENDING_INFO'").doesNotContain("ticketNameHtml");
    }

    @Test
    @DisplayName("Provide info, Confirm Fixed and Reject fix are gated on allowedActions, never on the status")
    void buttonsFollowAllowedActions() {
        int start = appJs.indexOf("function empFeaturedActionsHtml(");
        String actions = appJs.substring(start, appJs.indexOf("\nfunction ", start + 1));
        assertThat(actions).contains("awaitsMyReply(t) ?", "allowed.includes('verify') ?",
                "allowed.includes('reject') ?");
        assertThat(actions).contains("btn-emp-provide-info", "btn-emp-verify", "btn-emp-reject", "data-id=\"${id}\"");
        assertThat(actions).doesNotContain("status");
        int await = appJs.indexOf("function awaitsMyReply(");
        assertThat(appJs.substring(await, appJs.indexOf("\nfunction ", await + 1)))
                .contains("allowedActions").contains("'provideInfo'");
        assertThat(employeeJs).contains("openProvideInfoModal(", "openVerifyModal(", "openRejectModal(");
    }

    @Test
    @DisplayName("the tracker maps the five public statuses and their history entries as specified")
    void trackerMapping() {
        assertThat(employeeJs).contains(
                "{ status: 'NEW', label: 'Reported', actions: ['IncidentCreated'] }",
                "{ status: 'ASSIGNED', label: 'Assigned', actions: ['IncidentAssigned'] }",
                "actions: ['IncidentAcknowledged', 'InvestigationStarted', 'MoreInfoRequested', 'IncidentReopened']",
                "{ status: 'RESOLVED', label: 'Resolved', actions: ['IncidentResolved', 'ResolutionVerified'] }",
                "{ status: 'CLOSED', label: 'Closed', actions: ['IncidentClosed'] }");
        assertThat(employeeJs).contains("${current * 20}% complete", "ACTIVE", "Reopened ${Number(t.reopenCount)} time(s)");
    }

    @Test
    @DisplayName("late answers are discarded, the state is reset on sign-out and the report form has a submit guard")
    void lifecycleGuards() {
        assertThat(employeeJs).contains("const seq = ++STATE.employee.requestSeq;",
                "if (seq !== STATE.employee.requestSeq || !STATE.currentUser) return;");
        assertThat(employeeJs).contains("if (STATE.employee.submitInFlight) return;",
                "STATE.employee.selectedId = created.id;");
        int signOut = appJs.indexOf("function handleSignOut(");
        assertThat(appJs.substring(signOut, appJs.indexOf("\n}", signOut))).contains("resetEmployeeView();");
        assertThat(employeeJs).contains("btn-emp-select", "aria-pressed");
    }

    @Test
    @DisplayName("no inline handlers or remote resources in the employee markup; styles in their own emp- section")
    void cspAndStyles() {
        assertThat(employeeHtml).doesNotContainPattern("\\son[a-z]+\\s*=").doesNotContain("<script", "http://",
                "https://");
        assertThat(stylesCss).contains("Employee portal (ADR-050)", ".emp-layout {", ".emp-ticket-row {",
                ".emp-track-step {", ".emp-kpi-bar {");
        String section = stylesCss.substring(stylesCss.indexOf("Employee portal (ADR-050)"));
        assertThat(section).contains("@media (max-width: 640px)").contains("@media (max-width: 1024px)");
        assertThat(indexHtml).contains("/styles.css?v=5.4").contains("/app.js?v=5.4");
    }

    @Test
    @DisplayName("each quick-fill chip names a team, and its text is assigned automatically to a category of that team")
    void templateChipsTellTheTruth() throws IOException {
        Map<String, String> chipTeams = new LinkedHashMap<>();
        Matcher chip = Pattern.compile("data-template=\"(\\w+)\">[^<]*&rarr; ([^<]+)</button>").matcher(employeeHtml);
        while (chip.find()) {
            chipTeams.put(chip.group(1), chip.group(2).replace("&amp;", "&").trim());
        }
        assertThat(chipTeams).hasSize(6);
        assertThat(String.join(" ", chipTeams.values())).doesNotContain("Tier");

        Map<String, String[]> templates = new LinkedHashMap<>();
        Matcher template = Pattern.compile("(\\w+): \\{ title: '([^']*)',\\s*description: '([^']*)' \\}")
                .matcher(employeeJs);
        while (template.find()) {
            templates.put(template.group(1), new String[] {template.group(2), template.group(3)});
        }
        assertThat(templates.keySet()).containsExactlyInAnyOrderElementsOf(chipTeams.keySet());

        NaiveBayesClassifier naiveBayes = new NaiveBayesClassifier();
        try (InputStream corpus = EmployeePortalMarkupTest.class.getResourceAsStream("/sim/corpus.csv")) {
            naiveBayes.trainFromCsv(corpus);
        }
        CompositeClassifier classifier = new CompositeClassifier(new KeywordClassifier(), naiveBayes);
        chipTeams.forEach((key, team) -> {
            assertThat(TEAM_CATEGORIES).as("known team " + team).containsKey(team);
            ClassificationResult result = classifier.classify(templates.get(key)[0], templates.get(key)[1]);
            assertThat(TEAM_CATEGORIES.get(team)).as(key + " -> " + team + " but got " + result.category())
                    .contains(result.category());
            assertThat(result.confidence()).as(key + " is assigned automatically")
                    .isGreaterThanOrEqualTo(ClassificationResult.TRIAGE_THRESHOLD);
        });
    }
}
