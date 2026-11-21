package com.iitsm.domain.model;

import com.iitsm.domain.exception.MfaAlreadyEnabledException;
import com.iitsm.domain.exception.MfaNotEnrolledException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import com.iitsm.domain.exception.ValidationException;
import java.util.Objects;

/**
 * OOP: Abstract base class for all authenticated system users.
 * Subclasses represent specific personas: Employee, Technician (and TeamLead), Administrator.
 */
public abstract class User {

    private final String id;
    private String name;
    private final Email email;
    private String passwordHash;
    private final UserRole role;
    private boolean active;
    private int failedLoginAttempts;
    private boolean locked;
    private int tokenVersion = 1;
    private java.time.Instant lockedUntil;
    private Long version;
    private boolean mfaEnabled;
    private String mfaSecret;
    private Long mfaLastStep;

    protected User(String id, String name, Email email, String passwordHash, UserRole role) {
        if (id == null || id.trim().isEmpty()) {
            throw new ValidationException("id", "User ID cannot be blank");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new ValidationException("name", "User name cannot be blank");
        }
        this.id = id.trim();
        this.name = name.trim();
        this.email = Objects.requireNonNull(email, "Email must not be null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "Password hash must not be null");
        this.role = Objects.requireNonNull(role, "UserRole must not be null");
        this.active = true;
        this.failedLoginAttempts = 0;
        this.locked = false;
    }

    /**
     * Department this user belongs to, or {@code null} for personas that have none (only
     * employees do). Lets callers ask the user instead of testing its concrete type.
     */
    public String getDepartment() {
        return null;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Email getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public boolean isLocked() {
        return locked;
    }

    public void updatePassword(String newPasswordHash) {
        if (newPasswordHash == null || newPasswordHash.trim().isEmpty()) {
            throw new ValidationException("password", "Password hash cannot be blank");
        }
        this.passwordHash = newPasswordHash;
    }

    /**
     * Counts a failed login and locks the account at the limit, with no expiry: only an administrator lifts such a
     * lock. The application uses {@link #recordFailedLogin(int, java.time.Instant, java.time.Duration)}.
     */
    public void recordFailedLogin(int maxAllowedBeforeLock) {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= maxAllowedBeforeLock) {
            this.locked = true;
        }
    }

    /**
     * Counts a failed login; the failure that reaches the limit locks the account until {@code now + lockDuration}
     * (ADR-033). A lock that has expired is forgotten first, so the count starts again; a failure inside an active
     * lock changes nothing, so repeated attempts cannot extend it.
     */
    public void recordFailedLogin(int maxAllowedBeforeLock, java.time.Instant now, java.time.Duration lockDuration) {
        if (lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
            throw new ValidationException("lockDuration", "Lock duration must be positive");
        }
        Objects.requireNonNull(now, "now cannot be null");
        if (isLockExpired(now)) {
            clearLock();
        }
        if (isCurrentlyLocked(now)) {
            return;
        }
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= maxAllowedBeforeLock) {
            this.locked = true;
            this.lockedUntil = now.plus(lockDuration);
        }
    }

    private boolean isLockExpired(java.time.Instant now) {
        return locked && lockedUntil != null && now.isAfter(lockedUntil);
    }

    private void clearLock() {
        this.failedLoginAttempts = 0;
        this.locked = false;
        this.lockedUntil = null;
    }

    public void resetFailedLogins() {
        this.failedLoginAttempts = 0;
        this.locked = false;
        this.lockedUntil = null;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(int tokenVersion) {
        this.tokenVersion = Math.max(1, tokenVersion);
    }

    public void bumpTokenVersion() {
        this.tokenVersion++;
    }

    public java.time.Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(java.time.Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public boolean isCurrentlyLocked(java.time.Instant now) {
        if (!locked) {
            return false;
        }
        if (lockedUntil != null && now != null && now.isAfter(lockedUntil)) {
            return false;
        }
        return true;
    }

    public void unlock() {
        this.locked = false;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        bumpTokenVersion();
    }

    public void setFailedLoginAttempts(int attempts) {
        this.failedLoginAttempts = Math.max(0, attempts);
    }

    /** Whether this kind of user may switch on two-step verification (ADR-048). Only administrators may, for now. */
    public boolean canEnrollMfa() {
        return false;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    /** The authenticator secret in its stored, encrypted form (never the plain secret); null before a setup. */
    public String getMfaSecret() {
        return mfaSecret;
    }

    /** The newest time step whose code was accepted, or null when none was; a code for that step or older is refused. */
    public Long getMfaLastStep() {
        return mfaLastStep;
    }

    /**
     * Starts (or restarts) enrolment with a new encrypted secret. Two-step verification is not on until
     * {@link #enableMfa()}, so an abandoned setup changes nothing about signing in.
     */
    public void beginMfaEnrollment(String encryptedSecret) {
        if (!canEnrollMfa()) {
            throw new UnauthorizedOperationException("Two-step verification is not available for this account type");
        }
        if (mfaEnabled) {
            throw new MfaAlreadyEnabledException();
        }
        if (encryptedSecret == null || encryptedSecret.isBlank()) {
            throw new ValidationException("secret", "The authenticator secret cannot be blank");
        }
        this.mfaSecret = encryptedSecret;
        this.mfaLastStep = null;
    }

    /** Switches two-step verification on; a setup must have been started. */
    public void enableMfa() {
        if (mfaEnabled) {
            throw new MfaAlreadyEnabledException();
        }
        if (mfaSecret == null) {
            throw new MfaNotEnrolledException("Start the two-step verification setup first");
        }
        this.mfaEnabled = true;
    }

    /** Switches it off and forgets the secret and the last accepted step. */
    public void disableMfa() {
        this.mfaEnabled = false;
        this.mfaSecret = null;
        this.mfaLastStep = null;
    }

    /** Remembers an accepted time step; a step that is not newer than the last one is a replay and is refused. */
    public void recordMfaStep(long step) {
        if (mfaLastStep != null && step <= mfaLastStep) {
            throw new IllegalArgumentException("A time step can be accepted only once");
        }
        this.mfaLastStep = step;
    }

    /** Restores the stored state when a user is loaded from the database; business code uses the methods above. */
    public void restoreMfa(boolean enabled, String encryptedSecret, Long lastStep) {
        this.mfaEnabled = enabled;
        this.mfaSecret = encryptedSecret;
        this.mfaLastStep = lastStep;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public abstract boolean canViewIncident(Incident incident, String owningTeamId);

    public abstract boolean canExecuteAction(Incident incident, IncidentAction action, String owningTeamId);

    /**
     * OOP: polymorphic rule (ADR-042). May this user promote the given resolution into the knowledge base? Nobody by
     * default (employees); a technician only for a fix they resolved; team leads and administrators always.
     */
    public boolean canPromoteResolution(Resolution resolution) {
        return false;
    }

    /**
     * OOP: polymorphic rule (ADR-045, Phase M3). May this user move a ticket of their team to another technician of
     * that team? Only a team lead; the ticket's own team and status are checked by the application service.
     */
    public boolean canReassignTeamTickets() {
        return false;
    }
}

