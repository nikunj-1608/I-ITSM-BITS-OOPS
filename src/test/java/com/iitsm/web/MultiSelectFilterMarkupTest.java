package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
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
 * Guards the markup and stylesheet contract of the Admin Command Centre multi-select filters: the dropdowns
 * offer exactly the values the API accepts, start closed, and style a hollow checkbox with a solid blue dot.
 */
class MultiSelectFilterMarkupTest {

    private static final Pattern OPTION_VALUE = Pattern.compile("class=\"multiselect-option\"[^>]*data-value=\"([A-Z0-9_]+)\"");

    private static String indexHtml;
    private static String stylesCss;
    private static String appJs;

    @BeforeAll
    static void loadStaticResources() throws IOException {
        indexHtml = read("static/index.html");
        stylesCss = read("static/styles.css");
        appJs = read("static/app.js");
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = MultiSelectFilterMarkupTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String section(String startMarker, String endMarker) {
        int start = indexHtml.indexOf(startMarker);
        assertThat(start).as(startMarker).isGreaterThanOrEqualTo(0);
        int end = indexHtml.indexOf(endMarker, start);
        assertThat(end).as(endMarker).isGreaterThan(start);
        return indexHtml.substring(start, end);
    }

    private static String categoryMarkup() {
        return section("id=\"multiselect-category\"", "id=\"multiselect-priority\"");
    }

    private static String priorityMarkup() {
        return section("id=\"multiselect-priority\"", "id=\"btn-admin-search\"");
    }

    private static List<String> optionValues(String markup) {
        List<String> values = new ArrayList<>();
        Matcher matcher = OPTION_VALUE.matcher(markup);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    @Test
    @DisplayName("Native category and priority selects are replaced by the multi-select components")
    void nativeSelectsAreReplaced() {
        assertThat(indexHtml).doesNotContain("id=\"admin-filter-category\"").doesNotContain("id=\"admin-filter-priority\"");
        assertThat(indexHtml).contains("class=\"custom-multiselect\" id=\"multiselect-category\"")
                .contains("class=\"custom-multiselect\" id=\"multiselect-priority\"");
    }

    @Test
    @DisplayName("Each dropdown has a trigger, a hidden menu with Select All / Clear, and an options list")
    void dropdownStructure() {
        for (String markup : List.of(categoryMarkup(), priorityMarkup())) {
            assertThat(markup).contains("class=\"multiselect-trigger btn btn-sm btn-secondary\"", "aria-haspopup=\"listbox\"",
                    "aria-expanded=\"false\"", "class=\"trigger-label\"", "class=\"trigger-caret\"",
                    "class=\"multiselect-menu hidden\"", "class=\"btn-select-all", "class=\"btn-clear-all",
                    "class=\"multiselect-options\"", "aria-multiselectable=\"true\"");
        }
        assertThat(categoryMarkup()).contains("data-all-label=\"All Categories\"");
        assertThat(priorityMarkup()).contains("data-all-label=\"All Priorities\"");
    }

    @Test
    @DisplayName("Category options are exactly the IncidentCategory enum values the API accepts")
    void categoryOptionsMatchEnum() {
        List<String> expected = Arrays.stream(IncidentCategory.values()).map(Enum::name).toList();
        assertThat(optionValues(categoryMarkup())).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("Priority options are exactly the Priority enum values the API accepts")
    void priorityOptionsMatchEnum() {
        List<String> expected = Arrays.stream(Priority.values()).map(Enum::name).toList();
        assertThat(optionValues(priorityMarkup())).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("Every option starts unselected, is a listbox option and carries a hollow checkbox")
    void optionsStartUnselected() {
        for (String markup : List.of(categoryMarkup(), priorityMarkup())) {
            int options = optionValues(markup).size();
            assertThat(countOccurrences(markup, "role=\"option\"")).isEqualTo(options);
            assertThat(countOccurrences(markup, "aria-selected=\"false\"")).isEqualTo(options);
            assertThat(countOccurrences(markup, "aria-selected=\"true\"")).isZero();
            assertThat(countOccurrences(markup, "class=\"custom-checkbox\"")).isEqualTo(options);
        }
    }

    @Test
    @DisplayName("Stylesheet draws a 16px hollow checkbox and a solid 8px blue dot when selected")
    void stylesheetDefinesHollowCheckboxAndBlueDot() {
        String checkbox = rule(".custom-checkbox {");
        assertThat(checkbox).contains("width: 16px", "height: 16px", "border: 2px solid #94a3b8", "background: transparent",
                "border-radius: 3px", "display: flex", "align-items: center", "justify-content: center");

        String selected = stylesCss.substring(stylesCss.indexOf(".custom-checkbox.selected,"));
        assertThat(selected).contains(".multiselect-option[aria-selected=\"true\"] .custom-checkbox");
        assertThat(rule(".custom-checkbox.selected::after,")).contains("width: 8px", "height: 8px", "border-radius: 50%",
                "background: var(--primary)");
    }

    @Test
    @DisplayName("Stylesheet defines the dropdown, menu overlay, trigger and option classes")
    void stylesheetDefinesDropdownClasses() {
        assertThat(stylesCss).contains(".custom-multiselect {", ".multiselect-trigger {", ".multiselect-menu {",
                ".multiselect-header {", ".multiselect-options {", ".multiselect-option {");
        assertThat(rule(".multiselect-menu {")).contains("position: absolute", "z-index: 45");
        assertThat(rule(".custom-multiselect {")).contains("position: relative");
    }

    @Test
    @DisplayName("Script wires both dropdowns and sends repeated priority/category params, with no leftover select ids")
    void scriptWiresMultiSelectsAndSendsRepeatedParams() {
        assertThat(appJs).contains("function initMultiSelect(containerId, options, onChange)",
                "initMultiSelect('multiselect-category'", "initMultiSelect('multiselect-priority'",
                "setupAdminFilterMultiSelects();", "params.append('category', category)",
                "params.append('priority', priority)", "selectedCategories", "selectedPriorities");
        assertThat(appJs).doesNotContain("admin-filter-category", "admin-filter-priority");
    }

    @Test
    @DisplayName("Static assets are cache-busted to a version that carries the multi-select styles (3.3 or newer)")
    void assetsAreCacheBusted() {
        assertThat(indexHtml).contains("/styles.css?v=3.4").contains("/app.js?v=3.4")
                .doesNotContain("?v=3.2").doesNotContain("?v=3.3");
    }

    private static String rule(String selector) {
        int start = stylesCss.indexOf(selector);
        assertThat(start).as("css rule " + selector).isGreaterThanOrEqualTo(0);
        return stylesCss.substring(start, stylesCss.indexOf('}', start));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
