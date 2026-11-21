package com.iitsm.application.service;

import com.iitsm.application.port.SecretCipherPort;
import com.iitsm.domain.exception.InvalidCredentialsException;
import com.iitsm.domain.exception.InvalidMfaCodeException;
import com.iitsm.domain.exception.MfaAlreadyEnabledException;
import com.iitsm.domain.exception.MfaNotEnrolledException;
import com.iitsm.domain.exception.ResourceNotFoundException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.MfaRecoveryCode;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.MfaRecoveryCodeRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.security.Base32;
import com.iitsm.domain.security.RecoveryCodes;
import com.iitsm.domain.security.TotpGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional two-step verification with time-based one-time passwords, for administrators (ADR-048).
 *
 * <p>Pattern: Application Service. The arithmetic lives in the domain ({@link TotpGenerator}, {@link RecoveryCodes});
 * this class orchestrates the account rules, the encrypted secret and the failure counting.
 *
 * <ul>
 *   <li>Time steps come from the real wall clock (rule R1); the lock check uses the shared clock, like the login.
 *   <li>The secret is stored encrypted, bound to the user id ({@link SecretCipherPort}); it is shown once, at setup.
 *   <li>A code is accepted once: a step that is not newer than the last accepted one is refused.
 *   <li>A wrong code counts toward the same lockout as a wrong password (ADR-033). The methods that count it do not roll
 *       back when they throw, so the count is kept.
 * </ul>
 */
@Service
public class MfaService {

    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{" + TotpGenerator.DIGITS + "}");

    private final UserRepository users;
    private final MfaRecoveryCodeRepository recoveryCodes;
    private final SecretCipherPort cipher;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttempts;
    private final RefreshTokenService sessions;
    private final SecurityAuditLog audit;
    private final Clock wallClock;
    private final Clock sharedClock;
    private final String issuer;
    private final int windowSteps;
    private final int recoveryCodeCount;
    private final SecureRandom random = new SecureRandom();

    /** What the administrator needs to add the account to an authenticator app; shown once. */
    public record MfaSetup(String secret, String otpauthUri) {}

    @SuppressWarnings("java:S107") // collaborators plus three configuration values, all required
    public MfaService(
            UserRepository users,
            MfaRecoveryCodeRepository recoveryCodes,
            SecretCipherPort cipher,
            PasswordEncoder passwordEncoder,
            LoginAttemptService loginAttempts,
            RefreshTokenService sessions,
            SecurityAuditLog audit,
            @Qualifier("wallClock") Clock wallClock,
            Clock sharedClock,
            @Value("${iitsm.security.mfa.issuer:Nimbus FinServe I-ITSM}") String issuer,
            @Value("${iitsm.security.mfa.window-steps:1}") int windowSteps,
            @Value("${iitsm.security.mfa.recovery-code-count:8}") int recoveryCodeCount) {
        this.users = Objects.requireNonNull(users, "users cannot be null");
        this.recoveryCodes = Objects.requireNonNull(recoveryCodes, "recoveryCodes cannot be null");
        this.cipher = Objects.requireNonNull(cipher, "cipher cannot be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder cannot be null");
        this.loginAttempts = Objects.requireNonNull(loginAttempts, "loginAttempts cannot be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions cannot be null");
        this.audit = Objects.requireNonNull(audit, "audit cannot be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock cannot be null");
        this.sharedClock = Objects.requireNonNull(sharedClock, "sharedClock cannot be null");
        this.issuer = Objects.requireNonNull(issuer, "issuer cannot be null");
        if (windowSteps < 0 || recoveryCodeCount < 1) {
            throw new IllegalStateException(
                    "iitsm.security.mfa.window-steps must not be negative and recovery-code-count must be at least 1");
        }
        this.windowSteps = windowSteps;
        this.recoveryCodeCount = recoveryCodeCount;
    }

    /** Starts (or restarts) the setup: a new secret, not active until {@link #confirmSetup} accepts a code from it. */
    @Transactional
    public MfaSetup beginSetup(String userId) {
        User user = load(userId);
        String secret = Base32.encodeWithoutPadding(TotpGenerator.newSecret(random));
        user.beginMfaEnrollment(cipher.encrypt(secret, user.getId()));
        users.save(user);
        return new MfaSetup(secret, TotpGenerator.otpauthUri(issuer, user.getEmail().value(), secret));
    }

    /** Proves the app works, switches two-step verification on and returns the recovery codes, this once. */
    @Transactional(noRollbackFor = InvalidMfaCodeException.class)
    public List<String> confirmSetup(String userId, String code) {
        User user = load(userId);
        if (user.isMfaEnabled()) {
            throw new MfaAlreadyEnabledException();
        }
        if (user.getMfaSecret() == null) {
            throw new MfaNotEnrolledException("Start the two-step verification setup first");
        }
        user.recordMfaStep(acceptedStep(user, code));
        user.enableMfa();
        users.save(user);
        List<String> codes = RecoveryCodes.generate(recoveryCodeCount, random);
        recoveryCodes.replaceAll(user.getId(), codes.stream().map(passwordEncoder::encode).toList(), wallClock.instant());
        audit.mfaEnabled(user.getId());
        return codes;
    }

    /** Turns it off for the signed-in administrator; needs the password and a valid code (or recovery code). */
    @Transactional(noRollbackFor = {InvalidMfaCodeException.class, InvalidCredentialsException.class})
    public void disable(String userId, String rawPassword, String code) {
        User user = load(userId);
        if (!user.isMfaEnabled()) {
            throw new MfaNotEnrolledException("Two-step verification is not enabled for this account");
        }
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            loginAttempts.recordFailedAttempt(user.getEmail().value());
            throw new InvalidCredentialsException("Invalid credentials");
        }
        acceptSecondFactor(user, code);
        user.disableMfa();
        users.save(user);
        recoveryCodes.deleteAll(user.getId());
        audit.mfaDisabled(user.getId());
    }

    /**
     * An administrator switches off another administrator's two-step verification (a lost phone and no recovery codes).
     * Refused for oneself, which has its own path with the password and a code. The target's sessions end too.
     */
    @Transactional
    public void adminReset(String actorId, String targetId) {
        User actor = load(actorId);
        if (!actor.canEnrollMfa()) {
            throw new UnauthorizedOperationException("Only an administrator can reset two-step verification");
        }
        if (actor.getId().equals(targetId)) {
            throw new ValidationException("userId", "Turn off your own two-step verification in the security dialog");
        }
        User target = users.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + targetId));
        if (!target.isMfaEnabled()) {
            throw new MfaNotEnrolledException("Two-step verification is not enabled for this account");
        }
        target.disableMfa();
        users.save(target);
        recoveryCodes.deleteAll(target.getId());
        sessions.logoutAll(target.getId());
        audit.mfaReset(actor.getId(), target.getId());
    }

    /**
     * The second step of signing in: checks the code (or recovery code) of the user the challenge was issued for and
     * returns that user. Every refusal is the same {@link InvalidMfaCodeException}, including an unknown, deactivated,
     * locked or unenrolled account.
     */
    @Transactional(noRollbackFor = InvalidMfaCodeException.class)
    public User verifyChallenge(String userId, String code) {
        User user = users.findById(userId).orElseThrow(InvalidMfaCodeException::new);
        if (!user.isActive() || user.isCurrentlyLocked(sharedClock.instant()) || !user.isMfaEnabled()) {
            throw new InvalidMfaCodeException();
        }
        acceptSecondFactor(user, code);
        users.save(user);
        loginAttempts.resetFailedAttempts(user.getEmail().value());
        return user;
    }

    private User load(String userId) {
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    /** Accepts a one-time code (recording its step on the user, the caller saves) or a recovery code (used up here). */
    private void acceptSecondFactor(User user, String code) {
        if (RecoveryCodes.looksLikeRecoveryCode(code)) {
            useRecoveryCode(user, code);
        } else {
            user.recordMfaStep(acceptedStep(user, code));
        }
    }

    /** The time step a one-time code is valid for, newer than any accepted before; a failure is counted and refused. */
    private long acceptedStep(User user, String code) {
        String trimmed = code == null ? "" : code.strip();
        if (SIX_DIGITS.matcher(trimmed).matches()) {
            byte[] secret = Base32.decode(cipher.decrypt(user.getMfaSecret(), user.getId()));
            long last = user.getMfaLastStep() == null ? Long.MIN_VALUE : user.getMfaLastStep();
            var step = TotpGenerator.verify(secret, trimmed, wallClock.instant(), windowSteps, last);
            if (step.isPresent()) {
                return step.getAsLong();
            }
        }
        throw refused(user);
    }

    private void useRecoveryCode(User user, String code) {
        String normalised = RecoveryCodes.normalize(code);
        Instant now = wallClock.instant();
        for (MfaRecoveryCode stored : recoveryCodes.findUnused(user.getId())) {
            if (passwordEncoder.matches(normalised, stored.codeHash()) && recoveryCodes.markUsed(stored.id(), now)) {
                audit.recoveryCodeUsed(user.getId());
                return;
            }
        }
        throw refused(user);
    }

    private InvalidMfaCodeException refused(User user) {
        loginAttempts.recordFailedAttempt(user.getEmail().value());
        audit.mfaFailed(user.getId());
        return new InvalidMfaCodeException();
    }
}
