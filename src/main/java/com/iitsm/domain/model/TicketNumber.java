package com.iitsm.domain.model;

import com.iitsm.domain.exception.ValidationException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OOP: Value Object for the permanent, human-readable ticket identifier
 * {@code <DEPT>-<YEAR>-<SEQUENCE>}, for example {@code FIN-2026-000142}.
 *
 * <p>Only data that is known and fixed when a ticket is raised is encoded here, so the
 * identifier never has to change (priority, category and assignee all do and are deliberately
 * left out). Parsing accepts any sequence of at least six digits so counters can outgrow the
 * padding without breaking identifiers that are already issued.
 */
public record TicketNumber(String departmentCode, int year, long sequence) {

    public static final int MIN_CODE_LENGTH = 2;
    public static final int MAX_CODE_LENGTH = 4;
    public static final int MIN_YEAR = 1000;
    public static final int MAX_YEAR = 9999;
    public static final int SEQUENCE_PADDING = 6;

    private static final char SEPARATOR = '-';
    private static final Pattern CODE_PATTERN =
            Pattern.compile("^[A-Z]{" + MIN_CODE_LENGTH + "," + MAX_CODE_LENGTH + "}$");
    private static final Pattern TICKET_PATTERN = Pattern.compile(
            "^([A-Z]{" + MIN_CODE_LENGTH + "," + MAX_CODE_LENGTH + "})-(\\d{4})-(\\d{"
                    + SEQUENCE_PADDING + ",})$");

    public TicketNumber {
        if (departmentCode == null || !CODE_PATTERN.matcher(departmentCode).matches()) {
            throw new ValidationException("departmentCode",
                    "Department code must be " + MIN_CODE_LENGTH + "-" + MAX_CODE_LENGTH
                            + " uppercase letters: " + departmentCode);
        }
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new ValidationException("year", "Year must have four digits: " + year);
        }
        if (sequence < 1) {
            throw new ValidationException("sequence", "Sequence must be at least 1: " + sequence);
        }
    }

    /** Parses a formatted ticket number; leading and trailing blanks and letter case are ignored. */
    public static TicketNumber parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new ValidationException("ticketNumber", "Ticket number cannot be null or blank");
        }
        Matcher matcher = TICKET_PATTERN.matcher(text.trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new ValidationException("ticketNumber", "Invalid ticket number format: " + text);
        }
        try {
            return new TicketNumber(matcher.group(1), Integer.parseInt(matcher.group(2)),
                    Long.parseLong(matcher.group(3)));
        } catch (NumberFormatException e) {
            throw new ValidationException("ticketNumber", "Ticket number is out of range: " + text);
        }
    }

    /** True when the text is a well-formed new-style ticket number (legacy ids return false). */
    public static boolean isValid(String text) {
        if (text == null) {
            return false;
        }
        return TICKET_PATTERN.matcher(text.trim().toUpperCase(Locale.ROOT)).matches();
    }

    public String format() {
        return departmentCode + SEPARATOR + String.format("%04d", year) + SEPARATOR
                + String.format("%0" + SEQUENCE_PADDING + "d", sequence);
    }

    @Override
    public String toString() {
        return format();
    }
}
