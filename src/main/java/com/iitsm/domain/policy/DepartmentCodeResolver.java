package com.iitsm.domain.policy;

/**
 * Pattern: Strategy / Open-Closed Principle.
 * Turns a reporter's free-text department into the short code used in ticket numbers, so callers
 * depend on this interface and a new naming rule needs a new class, not an edit to existing ones.
 */
public interface DepartmentCodeResolver {

    /**
     * Returns an uppercase code of two to four letters. Never returns null: a blank or
     * unusable department resolves to a miscellaneous code (MISC).
     */
    String codeFor(String department);

    /**
     * Whether the department is one this resolver knows by name. Resolvers without a list of departments accept every
     * name, so the default is true; {@link RegistryDepartmentCodeResolver} answers from its registry.
     */
    default boolean isRegistered(String department) {
        return true;
    }

    /** The department names this resolver knows, for error messages; empty when it keeps no list. */
    default java.util.List<String> registeredDepartments() {
        return java.util.List.of();
    }
}
