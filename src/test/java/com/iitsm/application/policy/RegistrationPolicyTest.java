package com.iitsm.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.policy.RegistryDepartmentCodeResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase E (ADR-033, stress-audit item L4, decision D12): who may self-register. The allowed
 * e-mail domains come from configuration (empty = any, as in dev and test), and the department must be one the
 * ticket numbering knows, so a typo no longer creates a department of its own.
 */
class RegistrationPolicyTest {

    private static RegistrationPolicy policy(String allowedDomains) {
        return new RegistrationPolicy(new RegistryDepartmentCodeResolver(), allowedDomains);
    }

    @Test
    @DisplayName("An empty configuration accepts any domain")
    void emptyConfigurationAllowsAnyDomain() {
        assertThatCode(() -> policy("").enforce("someone@gmail.com", "Engineering")).doesNotThrowAnyException();
        assertThatCode(() -> policy("  ").enforce("someone@example.org", "Engineering")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("L4: with a configured domain only that domain may register, compared case-insensitively")
    void configuredDomainIsEnforced() {
        RegistrationPolicy policy = policy("nimbusfinserve.com");

        assertThatCode(() -> policy.enforce("jane@nimbusfinserve.com", "Engineering")).doesNotThrowAnyException();
        assertThatCode(() -> policy.enforce("Jane@NimbusFinServe.COM", "Engineering")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.enforce("jane@gmail.com", "Engineering"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("email");
    }

    @Test
    @DisplayName("L4: look-alike domains are refused (a sub-domain, a suffix, a prefix, an embedded address)")
    void lookAlikesAreRefused() {
        RegistrationPolicy policy = policy("nimbusfinserve.com");

        for (String email : new String[] {"a@sub.nimbusfinserve.com", "a@nimbusfinserve.com.evil.org",
                "a@evilnimbusfinserve.com", "a@nimbusfinserve.com@evil.org", "nimbusfinserve.com", "a@"}) {
            assertThatThrownBy(() -> policy.enforce(email, "Engineering")).as(email)
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    @DisplayName("Several domains can be configured, with spaces around the commas")
    void severalDomains() {
        RegistrationPolicy policy = policy("nimbusfinserve.com , partner.example");

        assertThatCode(() -> policy.enforce("a@partner.example", "Engineering")).doesNotThrowAnyException();
        assertThatCode(() -> policy.enforce("a@nimbusfinserve.com", "Engineering")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.enforce("a@other.example", "Engineering")).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("L4: the department must be a registered one, compared without case and extra spaces")
    void departmentMustBeRegistered() {
        RegistrationPolicy policy = policy("");

        assertThatCode(() -> policy.enforce("a@x.com", "engineering")).doesNotThrowAnyException();
        assertThatCode(() -> policy.enforce("a@x.com", "  Customer   Support ")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.enforce("a@x.com", "Enginering"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("department");
        assertThatThrownBy(() -> policy.enforce("a@x.com", " ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> policy.enforce("a@x.com", null)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("The two departments the registration form offers besides the original eight are registered")
    void formDepartmentsAreRegistered() {
        RegistryDepartmentCodeResolver resolver = new RegistryDepartmentCodeResolver();

        assertThat(resolver.isRegistered("Human Resources")).isTrue();
        assertThat(resolver.isRegistered("Risk & Compliance")).isTrue();
        assertThat(resolver.codeFor("Human Resources")).as("ticket numbers of existing tickets keep their code").isEqualTo("HUM");
        assertThat(resolver.codeFor("Risk & Compliance")).isEqualTo("RIS");
        assertThat(resolver.isRegistered("Underwater Basket Weaving")).isFalse();
    }
}
