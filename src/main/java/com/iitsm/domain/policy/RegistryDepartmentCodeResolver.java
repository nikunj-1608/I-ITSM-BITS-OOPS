package com.iitsm.domain.policy;

import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.TicketNumber;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Pattern: Registry. Default {@link DepartmentCodeResolver}: explicit department-to-code mappings
 * (extensible at runtime through {@link #register}), with a deterministic fallback for departments
 * nobody has registered, because registration accepts free-text departments.
 *
 * <p>Fallback: the first {@value #FALLBACK_LENGTH} letters of the name, uppercased; names with
 * fewer than {@value TicketNumber#MIN_CODE_LENGTH} letters resolve to {@value #GENERAL_CODE}.
 * Two departments may share a fallback code; ticket numbers stay unique because sequences are
 * counted per code and year.
 */
public class RegistryDepartmentCodeResolver implements DepartmentCodeResolver {

    public static final String GENERAL_CODE = "GEN";
    static final int FALLBACK_LENGTH = 3;

    private static final Pattern NON_LETTERS = Pattern.compile("[^A-Za-z]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CODE_FORMAT = Pattern.compile(
            "^[A-Z]{" + TicketNumber.MIN_CODE_LENGTH + "," + TicketNumber.MAX_CODE_LENGTH + "}$");

    // ConcurrentHashMap chosen for thread-safe registry reads with runtime additions
    private final Map<String, String> codesByDepartment = new ConcurrentHashMap<>();

    public RegistryDepartmentCodeResolver() {
        register("Engineering", "ENG");
        register("Finance", "FIN");
        register("HR", "HR");
        register("Sales", "SAL");
        register("Operations", "OPS");
        register("Customer Support", "CSU");
        register("Legal", "LEG");
        register("Executive", "EXE");
    }

    public void register(String department, String code) {
        if (department == null || department.trim().isEmpty()) {
            throw new ValidationException("department", "Department cannot be null or blank");
        }
        if (code == null || !CODE_FORMAT.matcher(code).matches()) {
            throw new ValidationException("departmentCode",
                    "Department code must be " + TicketNumber.MIN_CODE_LENGTH + "-"
                            + TicketNumber.MAX_CODE_LENGTH + " uppercase letters: " + code);
        }
        codesByDepartment.put(normalize(department), code);
    }

    @Override
    public String codeFor(String department) {
        if (department == null || department.trim().isEmpty()) {
            return GENERAL_CODE;
        }
        String registered = codesByDepartment.get(normalize(department));
        return registered != null ? registered : fallbackCode(department);
    }

    private static String fallbackCode(String department) {
        String letters = NON_LETTERS.matcher(department).replaceAll("").toUpperCase(Locale.ROOT);
        if (letters.length() < TicketNumber.MIN_CODE_LENGTH) {
            return GENERAL_CODE;
        }
        return letters.substring(0, Math.min(letters.length(), FALLBACK_LENGTH));
    }

    private static String normalize(String department) {
        return WHITESPACE.matcher(department.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }
}
