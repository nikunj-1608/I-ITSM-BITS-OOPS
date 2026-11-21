package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.SlaStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-049: the admin directory's filter bar offers every filter of the API with the values the API accepts, the
 * script sends them under the API's parameter names, and the table shows the SLA status.
 */
class AdminSearchFilterMarkupTest {

    private static final Pattern OPTION_VALUE =
            Pattern.compile("class=\"multiselect-option\"[^>]*data-value=\"([A-Z0-9_]+)\"");

    private static String indexHtml;
    private static String appJs;

    @BeforeAll
    static void loadStaticResources() throws IOException {
        indexHtml = read("static/index.html");
        appJs = read("static/app.js");
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = AdminSearchFilterMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String between(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        assertThat(start).as(startMarker).isGreaterThanOrEqualTo(0);
        int end = text.indexOf(endMarker, start);
        assertThat(end).as(endMarker).isGreaterThan(start);
        return text.substring(start, end);
    }

    private static List<String> optionValues(String markup) {
        List<String> values = new ArrayList<>();
        Matcher matcher = OPTION_VALUE.matcher(markup);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static String catalogPanel() {
        return between(indexHtml, "id=\"admin-catalog-panel\"", "id=\"admin-triage-panel\"");
    }

    @Test
    @DisplayName("The filter bar has the status, SLA, service, technician, date and Clear controls")
    void newControlsExist() {
        assertThat(catalogPanel()).contains(
                "class=\"custom-multiselect\" id=\"multiselect-status\" data-all-label=\"All Statuses\"",
                "class=\"custom-multiselect\" id=\"multiselect-sla\" data-all-label=\"All SLA States\"",
                "<select id=\"admin-filter-service\"", "<option value=\"\">All services</option>",
                "<select id=\"admin-filter-technician\"", "<option value=\"\">All technicians</option>",
                "type=\"date\" id=\"admin-filter-from\"", "aria-label=\"Created from\"",
                "type=\"date\" id=\"admin-filter-to\"", "aria-label=\"Created to\"",
                "id=\"btn-admin-clear-filters\"");
    }

    @Test
    @DisplayName("Status options are exactly the IncidentStatus values, SLA options exactly the SlaStatus values")
    void optionValuesMatchTheEnums() {
        String status = between(indexHtml, "id=\"multiselect-status\"", "id=\"multiselect-sla\"");
        String sla = between(indexHtml, "id=\"multiselect-sla\"", "id=\"admin-filter-service\"");
        assertThat(optionValues(status))
                .containsExactlyElementsOf(Arrays.stream(IncidentStatus.values()).map(Enum::name).toList());
        assertThat(optionValues(sla))
                .containsExactlyElementsOf(Arrays.stream(SlaStatus.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("The script sends every filter under the API's parameter names and wires the new dropdowns")
    void scriptSendsEveryParameter() {
        assertThat(appJs).contains(
                "params.append('status', status)", "params.append('slaStatus', slaStatus)",
                "params.append('serviceId', serviceId)", "params.append('assigneeId', assigneeId)",
                "params.append('fromDate',", "params.append('toDate',",
                "initMultiSelect('multiselect-status'", "initMultiSelect('multiselect-sla'");
    }

    @Test
    @DisplayName("The script reads the service and technician lists, and forgets them on sign-out")
    void scriptLoadsFilterOptions() {
        assertThat(appJs).contains("apiCall('/api/admin/services')", "apiCall('/api/admin/technicians')",
                "Promise.allSettled", "option.textContent = ");
        String loader = between(appJs, "async function loadAdminFilterOptions()", "\n}");
        assertThat(loader).contains("fillFilterSelect('admin-filter-service', services.value",
                "fillFilterSelect('admin-filter-technician', technicians.value");
        assertThat(between(appJs, "function handleSignOut(", "\n}")).contains("resetAdminFilterOptions();");
        assertThat(between(appJs, "async function loadAdminView()", "\n}")).contains("loadAdminFilterOptions();");
    }

    @Test
    @DisplayName("A start day after the end day is stopped in the browser before any request")
    void reversedDatesAreStoppedInTheBrowser() {
        String search = between(appJs, "async function searchAdminIncidents()", "\n}");
        assertThat(search).contains("fromDay > toDay");
        assertThat(search.indexOf("fromDay > toDay")).isLessThan(search.indexOf("apiCall("));
    }

    @Test
    @DisplayName("The table has 9 header cells including SLA, and every message row spans 9 columns")
    void tableHasTheSlaColumn() {
        String head = between(catalogPanel(), "<thead>", "</thead>");
        assertThat(head.split("<th[ >]", -1).length - 1).isEqualTo(9);
        assertThat(head).contains("<th>Status</th>\r\n                    <th>SLA</th>".replace("\r\n", lineBreakOf(head)));
        String section = between(appJs, "async function searchAdminIncidents()", "bindAdminTicketLinks(tbody)");
        assertThat(section).doesNotContain("colspan=\"8\"").contains("colspan=\"9\"").contains("badge-sla-");
        assertThat(catalogPanel()).doesNotContain("colspan=\"8\"");
    }

    @Test
    @DisplayName("No inline event handlers in the new markup")
    void noInlineHandlers() {
        assertThat(catalogPanel()).doesNotContainPattern("\\son[a-z]+\\s*=");
    }

    private static String lineBreakOf(String text) {
        return text.contains("\r\n") ? "\r\n" : "\n";
    }
}
