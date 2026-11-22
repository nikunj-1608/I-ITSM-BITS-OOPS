package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.ValidationException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase E (ADR-033, stress-audit item M4, decision D11): an account locked by failed logins
 * unlocks itself after the lock duration. Before, {@code locked_until} was stored but never set, so only an
 * administrator could lift a lock, and a stranger could lock any account for good with five bad passwords.
 */
class UserLockoutExpiryTest {

    private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");
    private static final Duration LOCK = Duration.ofMinutes(15);
    private static final int MAX = 5;

    private static Employee employee() {
        return new Employee("EMP-1", "Jane Doe", new Email("jane@nimbusfinserve.com"), "hash",
                Designation.ASSOCIATE, "Engineering", 1);
    }

    private static void failTimes(User user, int times, Instant now) {
        for (int i = 0; i < times; i++) {
            user.recordFailedLogin(MAX, now, LOCK);
        }
    }

    @Test
    @DisplayName("M4: the fifth failed login locks the account until now + the lock duration")
    void lockSetsAnExpiry() {
        Employee user = employee();

        failTimes(user, MAX - 1, T0);
        assertThat(user.isCurrentlyLocked(T0)).isFalse();
        assertThat(user.getLockedUntil()).isNull();

        user.recordFailedLogin(MAX, T0, LOCK);

        assertThat(user.isCurrentlyLocked(T0)).isTrue();
        assertThat(user.getLockedUntil()).isEqualTo(T0.plus(LOCK));
    }

    @Test
    @DisplayName("M4: locked inside the window, unlocked after it (the instant of expiry is still locked)")
    void lockExpires() {
        Employee user = employee();
        failTimes(user, MAX, T0);

        assertThat(user.isCurrentlyLocked(T0.plus(LOCK).minusSeconds(1))).isTrue();
        assertThat(user.isCurrentlyLocked(T0.plus(LOCK))).isTrue();
        assertThat(user.isCurrentlyLocked(T0.plus(LOCK).plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("M4: after the lock expired a wrong password starts a new count instead of locking at once")
    void expiredLockRestartsTheCount() {
        Employee user = employee();
        failTimes(user, MAX, T0);
        Instant later = T0.plus(LOCK).plusSeconds(60);

        user.recordFailedLogin(MAX, later, LOCK);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(user.isCurrentlyLocked(later)).isFalse();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("M4: a failed login inside an active lock does not extend it")
    void activeLockIsNotExtended() {
        Employee user = employee();
        failTimes(user, MAX, T0);
        Instant expiry = user.getLockedUntil();

        user.recordFailedLogin(MAX, T0.plusSeconds(300), LOCK);

        assertThat(user.getLockedUntil()).isEqualTo(expiry);
    }

    @Test
    @DisplayName("M4: a successful login (reset) clears the lock and its expiry")
    void resetClearsTheExpiry() {
        Employee user = employee();
        failTimes(user, MAX, T0);

        user.resetFailedLogins();

        assertThat(user.isLocked()).isFalse();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getFailedLoginAttempts()).isZero();
    }

    @Test
    @DisplayName("M4: a lock without an expiry (set by an older version) stays until an administrator lifts it")
    void legacyLockStaysLocked() {
        Employee user = employee();
        user.setLocked(true);

        assertThat(user.isCurrentlyLocked(T0.plus(Duration.ofDays(365)))).isTrue();
    }

    @Test
    @DisplayName("D11: administrators follow the same rule")
    void administratorsFollowTheSameRule() {
        Administrator admin = new Administrator("ADMIN-1", "Root", new Email("root@nimbusfinserve.com"), "hash",
                AdminLevel.SYSTEM_ADMIN);

        failTimes(admin, MAX, T0);

        assertThat(admin.isCurrentlyLocked(T0.plusSeconds(60))).isTrue();
        assertThat(admin.isCurrentlyLocked(T0.plus(LOCK).plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("A lock duration that is zero, negative or missing is refused")
    void lockDurationMustBePositive() {
        Employee user = employee();

        assertThatThrownBy(() -> user.recordFailedLogin(MAX, T0, Duration.ZERO)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> user.recordFailedLogin(MAX, T0, Duration.ofMinutes(-1))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> user.recordFailedLogin(MAX, T0, null)).isInstanceOf(ValidationException.class);
    }
}
