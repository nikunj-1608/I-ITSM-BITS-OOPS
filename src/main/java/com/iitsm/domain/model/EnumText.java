package com.iitsm.domain.model;

import com.iitsm.domain.exception.ValidationException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns text from a client into an enum constant, or into a {@link ValidationException} that names the field and lists
 * the valid values (HTTP 400), instead of the bare {@code IllegalArgumentException} that {@code Enum.valueOf} throws
 * (HTTP 500, stress-audit item M5). Matching ignores case and surrounding blanks.
 */
public final class EnumText {

    /** Client text longer than this is cut when it is echoed back in an error message. */
    public static final int MAX_ECHOED_LENGTH = 50;

    private EnumText() { }

    /** The constant whose name equals the text, ignoring case and surrounding blanks. */
    public static <E extends Enum<E>> E parse(Class<E> type, String text, String field) {
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(field, "field cannot be null");
        if (text == null || text.isBlank()) {
            throw new ValidationException(field, "a value is required. Valid values: " + validValues(type));
        }
        String wanted = text.trim().toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(wanted)) {
                return constant;
            }
        }
        throw new ValidationException(field, "unknown value '" + echo(text) + "'. Valid values: " + validValues(type));
    }

    /** The constant names, comma separated, in declaration order. */
    public static String validValues(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
    }

    /** Client text as it may be shown in a message: trimmed, and cut when it is long. */
    public static String echo(String text) {
        String trimmed = text.trim();
        return trimmed.length() <= MAX_ECHOED_LENGTH ? trimmed : trimmed.substring(0, MAX_ECHOED_LENGTH) + "...";
    }
}
