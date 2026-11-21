package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.domain.exception.InvalidCredentialsException;
import com.iitsm.domain.exception.InvalidMfaCodeException;
import com.iitsm.domain.exception.MfaAlreadyEnabledException;
import com.iitsm.domain.exception.MfaNotEnrolledException;
import com.iitsm.domain.exception.ResourceNotFoundException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.AdminLevel;
import com.iitsm.domain.model.Administrator;
import com.iitsm.domain.model.Designation;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Employee;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.security.Base32;
import com.iitsm.domain.security.TotpGenerator;
import com.iitsm.infrastructure.security.AesGcmSecretCipher;
import com.iitsm.testsupport.InMemoryMfaRecoveryCodeRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Authentication hardening, Phase S4 (ADR-048): the two-step verification rules, with a real cipher, real BCrypt (cost 4)
 * and in-memory stores. The wall clock is fixed; the shared clock is set to another time to show that the code steps
 * follow the wall clock (rule R1).
 */
class MfaServiceTest {

    private static final Instant T = Instant.parse("2026-11-20T10:00:10Z");
    private static final String PASSWORD = "Password123!";

    private final Map<String, User> stored = new HashMap<>();
    private final UserRepository users = mock(UserRepository.class);
    private final InMemoryMfaRecoveryCodeRepository recovery = new InMemoryMfaRecoveryCodeRepository();
    private final LoginAttemptService attempts = mock(LoginAttemptService.class);
    private final RefreshTokenService sessions = mock(RefreshTokenService.class);
    private final SecurityAuditLog audit = mock(SecurityAuditLog.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AesGcmSecretCipher cipher =
            new AesGcmSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final java.util.concurrent.atomic.AtomicReference<Instant> wallNow = new java.util.concurrent.atomic.AtomicReference<>(T);
    private final Clock wallClock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return wallNow.get();
        }
    };
    private final Clock sharedClock = Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC);
    private MfaService service;
    private Administrator admin;

    @BeforeEach
    void setUp() {
        admin = new Administrator("ADM-1", "Ada Admin", new Email("ada@nimbusfinserve.com"),
                encoder.encode(PASSWORD), AdminLevel.SYSTEM_ADMIN);
        stored.put("ADM-1", admin);
        when(users.findById(any())).thenAnswer(call -> java.util.Optional.ofNullable(stored.get(call.<String>getArgument(0))));
        when(users.save(any())).thenAnswer(call -> call.getArgument(0));
        service = new MfaService(users, recovery, cipher, encoder, attempts, sessions, audit, wallClock, sharedClock,
                "Nimbus FinServe I-ITSM", 1, 8);
    }

    private String codeAt(String secret, Instant at) {
        return TotpGenerator.codeAt(Base32.decode(secret), TotpGenerator.stepAt(at));
    }

    /** Sets up and confirms; returns the plain secret. */
    private String enrolled() {
        String secret = service.beginSetup("ADM-1").secret();
        service.confirmSetup("ADM-1", codeAt(secret, T));
        return secret;
    }

    // ---- setup -------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("setup returns a 32-character secret and an otpauth URI, stores it encrypted and does not switch it on")
    void beginSetup() {
        MfaService.MfaSetup setup = service.beginSetup("ADM-1");

        assertThat(setup.secret()).matches("[A-Z2-7]{32}");
        assertThat(setup.otpauthUri()).startsWith("otpauth://totp/Nimbus%20FinServe%20I-ITSM:ada%40nimbusfinserve.com")
                .contains("secret=" + setup.secret());
        assertThat(admin.getMfaSecret()).isNotEqualTo(setup.secret()).doesNotContain(setup.secret());
        assertThat(cipher.decrypt(admin.getMfaSecret(), "ADM-1")).isEqualTo(setup.secret());
        assertThat(admin.isMfaEnabled()).isFalse();
    }

    @Test
    @DisplayName("setup twice gives a different secret each time (the pending one is replaced)")
    void beginSetupTwice() {
        String first = service.beginSetup("ADM-1").secret();
        String second = service.beginSetup("ADM-1").secret();

        assertThat(second).isNotEqualTo(first);
        assertThat(cipher.decrypt(admin.getMfaSecret(), "ADM-1")).isEqualTo(second);
    }

    @Test
    @DisplayName("an employee cannot start a setup; an unknown user is not found; an enabled account cannot start again")
    void setupRefusals() {
        Employee employee = new Employee("EMP-1", "Jane", new Email("jane@nimbusfinserve.com"), "hash",
                Designation.ASSOCIATE, "Engineering", 1);
        stored.put("EMP-1", employee);

        assertThatThrownBy(() -> service.beginSetup("EMP-1")).isInstanceOf(UnauthorizedOperationException.class);
        assertThatThrownBy(() -> service.beginSetup("NOBODY")).isInstanceOf(ResourceNotFoundException.class);
        enrolled();
        assertThatThrownBy(() -> service.beginSetup("ADM-1")).isInstanceOf(MfaAlreadyEnabledException.class);
    }

    @Test
    @DisplayName("confirming with the first code switches it on, records the step and returns 8 recovery codes stored as hashes")
    void confirmSetup() {
        String secret = service.beginSetup("ADM-1").secret();

        List<String> codes = service.confirmSetup("ADM-1", codeAt(secret, T));

        assertThat(admin.isMfaEnabled()).isTrue();
        assertThat(admin.getMfaLastStep()).isEqualTo(TotpGenerator.stepAt(T));
        assertThat(codes).hasSize(8).allMatch(code -> code.matches("[A-Z2-7]{10}"));
        assertThat(recovery.all()).hasSize(8);
        for (var row : recovery.all()) {
            assertThat(codes).doesNotContain(row.codeHash());
            assertThat(row.codeHash()).startsWith("$2");
        }
        verify(audit).mfaEnabled("ADM-1");
    }

    @Test
    @DisplayName("confirming with a wrong code is refused, counted toward the lockout and leaves it off")
    void confirmWithAWrongCode() {
        String secret = service.beginSetup("ADM-1").secret();
        String right = codeAt(secret, T);
        String wrong = right.equals("000000") ? "000001" : "000000";

        assertThatThrownBy(() -> service.confirmSetup("ADM-1", wrong)).isInstanceOf(InvalidMfaCodeException.class);

        assertThat(admin.isMfaEnabled()).isFalse();
        verify(attempts).recordFailedAttempt("ada@nimbusfinserve.com");
        assertThat(recovery.all()).isEmpty();
    }

    @Test
    @DisplayName("confirming without a started setup, or twice, is a conflict")
    void confirmRefusals() {
        assertThatThrownBy(() -> service.confirmSetup("ADM-1", "123456")).isInstanceOf(MfaNotEnrolledException.class);
        enrolled();
        assertThatThrownBy(() -> service.confirmSetup("ADM-1", "123456")).isInstanceOf(MfaAlreadyEnabledException.class);
    }

    // ---- the second step of signing in ----------------------------------------------------------------------------------

    @Test
    @DisplayName("a correct code is accepted, clears the failure count, and the same code again is refused (replay)")
    void verifyChallenge() {
        String secret = enrolled();
        wallNow.set(T.plus(Duration.ofSeconds(60)));
        String code = codeAt(secret, wallNow.get());

        User user = service.verifyChallenge("ADM-1", code);

        assertThat(user).isSameAs(admin);
        verify(attempts).resetFailedAttempts("ada@nimbusfinserve.com");
        assertThatThrownBy(() -> service.verifyChallenge("ADM-1", code)).isInstanceOf(InvalidMfaCodeException.class);
    }

    @Test
    @DisplayName("the code of the step that enabled it is refused at the next sign-in (same step), the next step's code works")
    void enableCodeCannotBeReused() {
        String secret = enrolled();

        assertThatThrownBy(() -> service.verifyChallenge("ADM-1", codeAt(secret, T)))
                .isInstanceOf(InvalidMfaCodeException.class);
        wallNow.set(T.plus(Duration.ofSeconds(30)));
        assertThat(service.verifyChallenge("ADM-1", codeAt(secret, wallNow.get()))).isSameAs(admin);
    }

    @Test
    @DisplayName("an older code than one already accepted is refused")
    void olderCodeAfterANewerOne() {
        String secret = enrolled();
        wallNow.set(T.plus(Duration.ofSeconds(60)));
        String older = codeAt(secret, wallNow.get().minusSeconds(30));
        service.verifyChallenge("ADM-1", codeAt(secret, wallNow.get()));

        assertThatThrownBy(() -> service.verifyChallenge("ADM-1", older)).isInstanceOf(InvalidMfaCodeException.class);
    }

    @Test
    @DisplayName("a wrong code, a malformed code and an empty code are refused and counted")
    void wrongCodesAreCounted() {
        String secret = enrolled();
        wallNow.set(T.plus(Duration.ofSeconds(90)));
        String right = codeAt(secret, wallNow.get());
        String wrong = right.equals("000000") ? "000001" : "000000";

        for (String code : new String[] {wrong, "12345", "1234567", "abcdef", "", null}) {
            assertThatThrownBy(() -> service.verifyChallenge("ADM-1", code)).isInstanceOf(InvalidMfaCodeException.class);
        }

        verify(attempts, org.mockito.Mockito.times(6)).recordFailedAttempt("ada@nimbusfinserve.com");
        verify(attempts, never()).resetFailedAttempts(any());
    }

    @Test
    @DisplayName("the code steps follow the wall clock, not the shared simulation clock")
    void usesTheWallClock() {
        String secret = enrolled();
        wallNow.set(T.plus(Duration.ofSeconds(60)));
        String codeForSharedClock = codeAt(secret, sharedClock.instant());
        String codeForWallClock = codeAt(secret, wallNow.get());

        assertThatThrownBy(() -> service.verifyChallenge("ADM-1", codeForSharedClock))
                .isInstanceOf(InvalidMfaCodeException.class);
        assertThat(service.verifyChallenge("ADM-1", codeForWallClock)).isSameAs(admin);
    }

    @Test
    @DisplayName("an unknown, deactivated, locked or unenrolled account all give the same refusal, without counting")
    void accountRefusals() {
        String secret = enrolled();
        wallNow.set(T.plus(Duration.ofSeconds(60)));
        String code = codeAt(secret, wallNow.get());
        Employee plain = new Employee("EMP-1", "Jane", new Email("jane@nimbusfinserve.com"), "hash",
                Designation.ASSOCIATE, "Engineering", 1);
        stored.put("EMP-1", plain);

        assertThatThrownBy(() -> service.verifyChallenge("NOBODY", code)).isInstanceOf(InvalidMfaCodeException.class);
        assertThatThrownBy(() -> service.verifyChallenge("EMP-1", code)).isInstanceOf(InvalidMfaCodeException.class);
        for (int i = 0; i < 5; i++) {
            admin.recordFailedLogin(5, sharedClock.instant(), Duration.ofMinutes(15));
        }
        assertThatThrownBy(() -> service.verifyChallenge("ADM-1", code))
                .isInstanceOf(InvalidMfaCodeException.class).hasMessage(InvalidMfaCodeException.MESSAGE);
        admin.unlock();
        admin.deactivate();
        assertThatThrownBy(() -> service.verifyChallenge("ADM-1", code)).isInstanceOf(InvalidMfaCodeException.class);
        verify(attempts, never()).recordFailedAttempt(any());
    }

    // ---- recovery codes --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a recovery code works once, typed in any case with hyphens; the second use is refused and counted")
    void recoveryCodeWorksOnce() {
        String secret = service.beginSetup("ADM-1").secret();
        List<String> codes = service.confirmSetup("ADM-1", codeAt(secret, T));
        String typed = codes.get(0).substring(0, 5).toLowerCase() + "-" + codes.get(0).substring(5);

        assertThat(service.verifyChallenge("ADM-1", typed)).isSameAs(admin);

        assertThatThrownBy(() -> service.verifyChallenge("ADM-1", codes.get(0))).isInstanceOf(InvalidMfaCodeException.class);
        assertThat(service.verifyChallenge("ADM-1", codes.get(1))).isSameAs(admin);
        verify(attempts).recordFailedAttempt("ada@nimbusfinserve.com");
        verify(audit, org.mockito.Mockito.times(2)).recoveryCodeUsed("ADM-1");
        assertThat(recovery.findUnused("ADM-1")).hasSize(6);
    }

    @Test
    @DisplayName("a made-up recovery code is refused and counted")
    void wrongRecoveryCode() {
        enrolled();

        assertThatThrownBy(() -> service.verifyChallenge("ADM-1", "AAAAAAAAAA")).isInstanceOf(InvalidMfaCodeException.class);

        verify(attempts).recordFailedAttempt("ada@nimbusfinserve.com");
    }

    // ---- disable and reset ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("disabling needs the password and a valid code; then the secret and the recovery codes are gone")
    void disable() {
        String secret = enrolled();
        wallNow.set(T.plus(Duration.ofSeconds(60)));

        service.disable("ADM-1", PASSWORD, codeAt(secret, wallNow.get()));

        assertThat(admin.isMfaEnabled()).isFalse();
        assertThat(admin.getMfaSecret()).isNull();
        assertThat(recovery.all()).isEmpty();
        verify(audit).mfaDisabled("ADM-1");
    }

    @Test
    @DisplayName("a wrong password is refused and counted; the code is not even looked at")
    void disableWrongPassword() {
        String secret = enrolled();
        wallNow.set(T.plus(Duration.ofSeconds(60)));

        assertThatThrownBy(() -> service.disable("ADM-1", "wrong", codeAt(secret, wallNow.get())))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.disable("ADM-1", null, codeAt(secret, wallNow.get())))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(admin.isMfaEnabled()).isTrue();
        verify(attempts, org.mockito.Mockito.times(2)).recordFailedAttempt("ada@nimbusfinserve.com");
    }

    @Test
    @DisplayName("a wrong code is refused and counted; it stays on")
    void disableWrongCode() {
        enrolled();

        assertThatThrownBy(() -> service.disable("ADM-1", PASSWORD, "000000")).isInstanceOf(InvalidMfaCodeException.class);

        assertThat(admin.isMfaEnabled()).isTrue();
        verify(attempts).recordFailedAttempt("ada@nimbusfinserve.com");
    }

    @Test
    @DisplayName("a recovery code can also turn it off")
    void disableWithARecoveryCode() {
        String secret = service.beginSetup("ADM-1").secret();
        List<String> codes = service.confirmSetup("ADM-1", codeAt(secret, T));

        service.disable("ADM-1", PASSWORD, codes.get(3));

        assertThat(admin.isMfaEnabled()).isFalse();
    }

    @Test
    @DisplayName("disabling an account that has no two-step verification is a conflict")
    void disableWhenOff() {
        assertThatThrownBy(() -> service.disable("ADM-1", PASSWORD, "123456")).isInstanceOf(MfaNotEnrolledException.class);
    }

    private Administrator secondAdmin() {
        Administrator other = new Administrator("ADM-2", "Bob Admin", new Email("bob@nimbusfinserve.com"),
                encoder.encode(PASSWORD), AdminLevel.IT_MANAGER);
        other.beginMfaEnrollment(cipher.encrypt("JBSWY3DPEHPK3PXP", "ADM-2"));
        other.enableMfa();
        stored.put("ADM-2", other);
        recovery.replaceAll("ADM-2", List.of(encoder.encode("AAAAAAAAAA")), T);
        return other;
    }

    @Test
    @DisplayName("an administrator can reset another administrator: off, codes deleted, sessions ended, audited")
    void adminReset() {
        Administrator other = secondAdmin();

        service.adminReset("ADM-1", "ADM-2");

        assertThat(other.isMfaEnabled()).isFalse();
        assertThat(other.getMfaSecret()).isNull();
        assertThat(recovery.findUnused("ADM-2")).isEmpty();
        verify(sessions).logoutAll("ADM-2");
        verify(audit).mfaReset("ADM-1", "ADM-2");
    }

    @Test
    @DisplayName("resetting oneself is refused, as is a reset by a non-administrator, of an unknown user or of an account without it")
    void adminResetRefusals() {
        secondAdmin();
        Employee employee = new Employee("EMP-1", "Jane", new Email("jane@nimbusfinserve.com"), "hash",
                Designation.ASSOCIATE, "Engineering", 1);
        stored.put("EMP-1", employee);
        enrolled();

        assertThatThrownBy(() -> service.adminReset("ADM-1", "ADM-1")).isInstanceOf(ValidationException.class);
        assertThat(admin.isMfaEnabled()).isTrue();
        assertThatThrownBy(() -> service.adminReset("EMP-1", "ADM-2")).isInstanceOf(UnauthorizedOperationException.class);
        assertThatThrownBy(() -> service.adminReset("ADM-1", "NOBODY")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.adminReset("ADM-1", "EMP-1")).isInstanceOf(MfaNotEnrolledException.class);
        verify(sessions, never()).logoutAll(any());
    }

    @Test
    @DisplayName("bad settings stop the start-up")
    void badSettings() {
        assertThatThrownBy(() -> new MfaService(users, recovery, cipher, encoder, attempts, sessions, audit, wallClock,
                sharedClock, "x", -1, 8)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MfaService(users, recovery, cipher, encoder, attempts, sessions, audit, wallClock,
                sharedClock, "x", 1, 0)).isInstanceOf(IllegalStateException.class);
        assertThat(new SecureRandom()).isNotNull();
    }
}
