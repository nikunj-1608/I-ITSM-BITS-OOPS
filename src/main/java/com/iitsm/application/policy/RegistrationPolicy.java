package com.iitsm.application.policy;

import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.EnumText;
import com.iitsm.domain.policy.DepartmentCodeResolver;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pattern: Policy. Who may self-register: the e-mail domain must be one of the configured domains (none configured
 * means any domain, as in dev and test) and the department must be one the ticket numbering knows (ADR-033).
 */
@Component
public class RegistrationPolicy {

    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s]+@([^@\\s]+)$");

    private final DepartmentCodeResolver departments;
    private final Set<String> allowedDomains;

    public RegistrationPolicy(
            DepartmentCodeResolver departments,
            @Value("${iitsm.security.allowed-email-domains:}") String allowedDomains) {
        this.departments = Objects.requireNonNull(departments, "departments cannot be null");
        this.allowedDomains = Arrays.stream(Objects.requireNonNullElse(allowedDomains, "").split(","))
                .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                .filter(domain -> !domain.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Refuses a registration that does not follow the policy with a {@link ValidationException} naming the field. */
    public void enforce(String email, String department) {
        enforceDomain(email);
        enforceDepartment(department);
    }

    private void enforceDomain(String email) {
        if (allowedDomains.isEmpty()) {
            return;
        }
        var address = ADDRESS.matcher(email == null ? "" : email.trim());
        if (!address.matches() || !allowedDomains.contains(address.group(1).toLowerCase(Locale.ROOT))) {
            throw new ValidationException("email",
                    "Registration is open to these e-mail domains only: " + String.join(", ", new java.util.TreeSet<>(allowedDomains)));
        }
    }

    private void enforceDepartment(String department) {
        if (departments.isRegistered(department)) {
            return;
        }
        String choices = String.join(", ", departments.registeredDepartments());
        throw new ValidationException("department",
                "Unknown department '" + EnumText.echo(department == null ? "" : department) + "'"
                        + (choices.isEmpty() ? "" : ". Choose one of: " + choices));
    }
}
