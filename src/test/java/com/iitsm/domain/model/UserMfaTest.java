package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.MfaAlreadyEnabledException;
import com.iitsm.domain.exception.MfaNotEnrolledException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authentication hardening, Phase S4 (ADR-048): the two-step verification state and rules of a user. */
class UserMfaTest {

    private static Administrator admin() {
        return new Administrator("ADM-1", "Ada Admin", new Email("ada@nimbusfinserve.com"), "hash", AdminLevel.SYSTEM_ADMIN);
    }

    private static Employee employee() {
        return new Employee("EMP-1", "Jane Doe", new Email("jane@nimbusfinserve.com"), "hash",
                Designation.ASSOCIATE, "Engineering", 1);
    }

    @Test
    @DisplayName("only an administrator may enrol; employees, technicians and team leads may not")
    void onlyAdministratorsMayEnrol() {
        Technician technician = new Technician("TECH-1", "Tess", new Email("tess@nimbusfinserve.com"), "hash",
                java.util.Set.of(), 3, 5, "TEAM-NET");

        assertThat(admin().canEnrollMfa()).isTrue();
        assertThat(employee().canEnrollMfa()).isFalse();
        assertThat(technician.canEnrollMfa()).isFalse();
        assertThatThrownBy(() -> employee().beginMfaEnrollment("secret")).isInstanceOf(UnauthorizedOperationException.class);
    }

    @Test
    @DisplayName("a new account has no two-step verification and no secret")
    void startsOff() {
        Administrator admin = admin();

        assertThat(admin.isMfaEnabled()).isFalse();
        assertThat(admin.getMfaSecret()).isNull();
        assertThat(admin.getMfaLastStep()).isNull();
    }

    @Test
    @DisplayName("beginning enrolment stores the secret but does not switch it on")
    void beginIsNotEnable() {
        Administrator admin = admin();

        admin.beginMfaEnrollment("encrypted-1");

        assertThat(admin.getMfaSecret()).isEqualTo("encrypted-1");
        assertThat(admin.isMfaEnabled()).isFalse();
    }

    @Test
    @DisplayName("beginning again before enabling replaces the secret and forgets the last step")
    void beginAgain() {
        Administrator admin = admin();
        admin.beginMfaEnrollment("encrypted-1");
        admin.recordMfaStep(5);

        admin.beginMfaEnrollment("encrypted-2");

        assertThat(admin.getMfaSecret()).isEqualTo("encrypted-2");
        assertThat(admin.getMfaLastStep()).isNull();
    }

    @Test
    @DisplayName("a blank secret is refused")
    void blankSecret() {
        assertThatThrownBy(() -> admin().beginMfaEnrollment(" ")).isInstanceOf(
                com.iitsm.domain.exception.ValidationException.class);
    }

    @Test
    @DisplayName("enabling needs a started setup, and works once")
    void enable() {
        Administrator admin = admin();
        assertThatThrownBy(admin::enableMfa).isInstanceOf(MfaNotEnrolledException.class);

        admin.beginMfaEnrollment("encrypted-1");
        admin.enableMfa();

        assertThat(admin.isMfaEnabled()).isTrue();
        assertThatThrownBy(admin::enableMfa).isInstanceOf(MfaAlreadyEnabledException.class);
        assertThatThrownBy(() -> admin.beginMfaEnrollment("encrypted-2")).isInstanceOf(MfaAlreadyEnabledException.class);
    }

    @Test
    @DisplayName("disabling clears the flag, the secret and the last step")
    void disable() {
        Administrator admin = admin();
        admin.beginMfaEnrollment("encrypted-1");
        admin.recordMfaStep(9);
        admin.enableMfa();

        admin.disableMfa();

        assertThat(admin.isMfaEnabled()).isFalse();
        assertThat(admin.getMfaSecret()).isNull();
        assertThat(admin.getMfaLastStep()).isNull();
    }

    @Test
    @DisplayName("a time step is accepted once: the same or an older step is refused, a newer one is recorded")
    void recordStep() {
        Administrator admin = admin();

        admin.recordMfaStep(10);
        assertThatThrownBy(() -> admin.recordMfaStep(10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.recordMfaStep(9)).isInstanceOf(IllegalArgumentException.class);
        admin.recordMfaStep(11);

        assertThat(admin.getMfaLastStep()).isEqualTo(11);
    }

    @Test
    @DisplayName("restoring the stored state (loading from the database) sets all three fields")
    void restore() {
        Administrator admin = admin();

        admin.restoreMfa(true, "encrypted", 42L);

        assertThat(admin.isMfaEnabled()).isTrue();
        assertThat(admin.getMfaSecret()).isEqualTo("encrypted");
        assertThat(admin.getMfaLastStep()).isEqualTo(42L);
    }
}
