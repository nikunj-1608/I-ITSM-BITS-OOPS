package com.iitsm.domain.model;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OOP: Value logic for the staff-facing ticket name, the permanent ticket id with the ticket's
 * <em>current</em> priority in front, for example {@code P2-FIN-2026-000142}.
 *
 * <p>The name is derived every time a staff view is rendered and is never stored, so an override or
 * any other priority change is reflected at once while the id itself (the primary key referenced
 * everywhere) never changes. Employee views never use it, because priority is hidden from them.
 * Hyphens only, no spaces. Lookups and searches accept either form through {@link #canonicalId}.
 */
public final class TicketDisplayName {

    private static final char SEPARATOR = '-';
    private static final String PRIORITY_PREFIX =
            "P[" + Priority.P1.getRank() + "-" + Priority.values().length + "]";
    private static final Pattern PREFIXED = Pattern.compile(
            "^" + PRIORITY_PREFIX + SEPARATOR + "((?:[A-Z]{" + TicketNumber.MIN_CODE_LENGTH + ","
                    + TicketNumber.MAX_CODE_LENGTH + "}-\\d{4}-\\d{" + TicketNumber.SEQUENCE_PADDING
                    + ",})|(?:INC-[0-9A-F]{8}))$");

    private TicketDisplayName() { }

    /** Priority prefix plus id; a ticket without a priority yet is shown by its plain id. */
    public static String of(Priority priority, String ticketId) {
        if (ticketId == null) {
            return null;
        }
        return priority == null ? ticketId : priority.name() + SEPARATOR + ticketId;
    }

    /**
     * Returns the plain ticket id when the text is a complete display name ({@code P2-FIN-2026-000142});
     * any other text (a plain id, a partial name, free words) comes back trimmed but otherwise unchanged.
     */
    public static String canonicalId(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        Matcher matcher = PREFIXED.matcher(trimmed.toUpperCase(Locale.ROOT));
        return matcher.matches() ? matcher.group(1) : trimmed;
    }
}
