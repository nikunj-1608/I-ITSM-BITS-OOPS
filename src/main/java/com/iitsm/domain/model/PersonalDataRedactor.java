package com.iitsm.domain.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OOP: Immutable policy object (ADR-042). Removes a reporter's personal data from text copied into the knowledge base:
 * the full name, every name part of at least {@value #MIN_NAME_PART} letters, the e-mail address and its local part, and
 * the department, each as a whole word and ignoring case, replaced by {@value #REPLACEMENT}. Only data the system knows
 * about the reporter is found; a colleague's name or a phone number written in the text is not.
 */
public final class PersonalDataRedactor {

    public static final String REPLACEMENT = "[removed]";
    static final int MIN_NAME_PART = 3;
    private static final String WORD_EDGE_BEFORE = "(?<![\\p{L}\\p{N}])";
    private static final String WORD_EDGE_AFTER = "(?![\\p{L}\\p{N}])";

    private static final PersonalDataRedactor NONE = new PersonalDataRedactor(List.of());

    private final List<Pattern> patterns;

    private PersonalDataRedactor(List<String> terms) {
        this.patterns = terms.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(term -> Pattern.compile(WORD_EDGE_BEFORE + Pattern.quote(term) + WORD_EDGE_AFTER,
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .toList();
    }

    /** The redactor for a ticket's reporter; a reporter that no longer exists leaves the text as it is. */
    public static PersonalDataRedactor forReporter(User reporter) {
        if (reporter == null) {
            return NONE;
        }
        Set<String> terms = new LinkedHashSet<>();
        addName(terms, reporter.getName());
        if (reporter.getEmail() != null) {
            String email = reporter.getEmail().value().trim();
            addIfPresent(terms, email);
            int at = email.indexOf('@');
            if (at > 0) {
                addIfPresent(terms, email.substring(0, at));
            }
        }
        addIfPresent(terms, reporter.getDepartment());
        return new PersonalDataRedactor(new ArrayList<>(terms));
    }

    /** The text with every known personal term replaced; null stays null. */
    public String redact(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Pattern pattern : patterns) {
            result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement(REPLACEMENT));
        }
        return result;
    }

    private static void addName(Set<String> terms, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        addIfPresent(terms, name);
        for (String part : name.trim().split("[\\s.]+")) {
            if (part.length() >= MIN_NAME_PART) {
                terms.add(part);
            }
        }
    }

    private static void addIfPresent(Set<String> terms, String value) {
        if (value != null && !value.isBlank()) {
            terms.add(value.trim());
        }
    }
}
