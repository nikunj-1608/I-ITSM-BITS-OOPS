package com.iitsm.domain.policy;

/**
 * Pattern: Strategy / Open-Closed Principle.
 * Turns a reporter's free-text department into the short code used in ticket numbers, so callers
 * depend on this interface and a new naming rule needs a new class, not an edit to existing ones.
 */
public interface DepartmentCodeResolver {

    /**
     * Returns an uppercase code of two to four letters. Never returns null: a blank or
     * unusable department resolves to a general-purpose code.
     */
    String codeFor(String department);
}
