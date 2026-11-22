package com.iitsm.application.service;

import com.iitsm.application.port.OpaqueTokenPort;
import com.iitsm.application.port.TokenPort;
import com.iitsm.domain.exception.InvalidRefreshTokenException;
import com.iitsm.domain.model.RefreshToken;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.RefreshTokenRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.security.RefreshTokenRotation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sessions made of a short access token and a rotating refresh token (ADR-047).
 *
 * <p>Pattern: Application Service orchestrating the domain's {@link RefreshTokenRotation} decision. Time comes from
 * the real wall clock (rule R1: the simulation clock can be moved by years); only the account-lock check uses the
 * shared clock, because the lock's expiry was set with it (ADR-033).
 *
 * <p>Every write method is its own short transaction; none runs around a password check.
 */
@Service
public class RefreshTokenService {

    static final String REASON_LOGOUT = "LOGOUT";
    static final String REASON_LOGOUT_ALL = "LOGOUT_ALL";
    static final String REASON_REUSE = "REUSE_DETECTED";
    static final String REASON_ACCOUNT = "ACCOUNT_CHANGED";
    static final String REASON_SESSION_LIMIT = "SESSION_LIMIT";

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final OpaqueTokenPort opaqueTokens;
    private final TokenPort accessTokens;
    private final SecurityAuditLog audit;
    private final Clock wallClock;
    private final Clock sharedClock;
    private final Duration refreshTtl;
    private final int maxSessionsPerUser;

    @SuppressWarnings("java:S107") // collaborators plus two configuration values, all required
    public RefreshTokenService(
            RefreshTokenRepository tokens,
            UserRepository users,
            OpaqueTokenPort opaqueTokens,
            TokenPort accessTokens,
            SecurityAuditLog audit,
            @Qualifier("wallClock") Clock wallClock,
            Clock sharedClock,
            @Value("${iitsm.security.refresh.ttl-hours:8}") long ttlHours,
            @Value("${iitsm.security.refresh.max-sessions-per-user:10}") int maxSessionsPerUser) {
        this.tokens = Objects.requireNonNull(tokens, "tokens cannot be null");
        this.users = Objects.requireNonNull(users, "users cannot be null");
        this.opaqueTokens = Objects.requireNonNull(opaqueTokens, "opaqueTokens cannot be null");
        this.accessTokens = Objects.requireNonNull(accessTokens, "accessTokens cannot be null");
        this.audit = Objects.requireNonNull(audit, "audit cannot be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock cannot be null");
        this.sharedClock = Objects.requireNonNull(sharedClock, "sharedClock cannot be null");
        if (ttlHours < 1 || maxSessionsPerUser < 1) {
            throw new IllegalStateException(
                    "iitsm.security.refresh.ttl-hours and max-sessions-per-user must be at least 1");
        }
        this.refreshTtl = Duration.ofHours(ttlHours);
        this.maxSessionsPerUser = maxSessionsPerUser;
    }

    /** Starts a session for a user whose credentials were just verified; trims the user's oldest sessions to the cap. */
    @Transactional
    public AuthSession issueSession(User user) {
        Objects.requireNonNull(user, "user cannot be null");
        Instant now = wallClock.instant();
        String familyId = UUID.randomUUID().toString();
        Stored first = store(user, familyId, now, now.plus(refreshTtl));
        enforceSessionLimit(user.getId(), now);
        audit.loginSuccess(user.getId(), familyId);
        return first.session();
    }

    /**
     * Exchanges a refresh token for a new access token and the next refresh token. The presented token is revoked
     * (rotation); presenting a token that was already rotated revokes the whole session. Every failure is the same
     * {@link InvalidRefreshTokenException}; the revocation that goes with it is kept ({@code noRollbackFor}).
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public AuthSession refresh(String presentedToken) {
        Instant now = wallClock.instant();
        RefreshToken presented = findPresented(presentedToken);
        RefreshTokenRotation decision = RefreshTokenRotation.decide(presented, now);
        if (!decision.permitsRotation()) {
            rejectUnusable(presented, decision, now);
        }
        User user = usableUser(presented, now);
        if (!tokens.claimForRotation(presented.id(), now)) {
            rejectUnusable(presented, RefreshTokenRotation.REUSED, now);
        }
        Stored next = store(user, presented.familyId(), now, presented.expiresAt());
        tokens.markReplacedBy(presented.id(), next.token().id());
        audit.refreshRotated(user.getId(), presented.familyId());
        return next.session();
    }

    /** Ends the session the token belongs to. Idempotent and silent: an unknown or already ended token is fine. */
    @Transactional
    public void logout(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }
        tokens.findByTokenHash(opaqueTokens.hash(presentedToken)).ifPresent(token -> {
            tokens.revokeFamily(token.familyId(), REASON_LOGOUT, wallClock.instant());
            audit.logout(token.userId(), token.familyId());
        });
    }

    /**
     * Ends every session of the user, and also every access token issued before (including tokens without a session
     * id) by raising the user's token version.
     */
    @Transactional
    public void logoutAll(String userId) {
        Objects.requireNonNull(userId, "userId cannot be null");
        tokens.revokeAllForUser(userId, REASON_LOGOUT_ALL, wallClock.instant());
        users.findById(userId).ifPresent(user -> {
            user.bumpTokenVersion();
            users.save(user);
        });
        audit.logoutAll(userId);
    }

    private RefreshToken findPresented(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        return tokens.findByTokenHash(opaqueTokens.hash(presentedToken)).orElseThrow(InvalidRefreshTokenException::new);
    }

    private void rejectUnusable(RefreshToken presented, RefreshTokenRotation decision, Instant now) {
        if (decision == RefreshTokenRotation.REUSED) {
            tokens.revokeFamily(presented.familyId(), REASON_REUSE, now);
            audit.refreshReuseDetected(presented.userId(), presented.familyId());
        } else if (decision == RefreshTokenRotation.REVOKED) {
            tokens.revokeFamily(presented.familyId(), REASON_LOGOUT, now);
        }
        throw new InvalidRefreshTokenException();
    }

    /** The token's user, if they may still hold a session: exists, active, not locked, same token version. */
    private User usableUser(RefreshToken presented, Instant now) {
        Optional<User> found = users.findById(presented.userId());
        boolean usable = found.isPresent()
                && found.get().isActive()
                && !found.get().isCurrentlyLocked(sharedClock.instant())
                && found.get().getTokenVersion() == presented.tokenVersion();
        if (!usable) {
            tokens.revokeFamily(presented.familyId(), REASON_ACCOUNT, now);
            throw new InvalidRefreshTokenException();
        }
        return found.get();
    }

    private Stored store(User user, String familyId, Instant now, Instant expiresAt) {
        String refreshToken = opaqueTokens.newToken();
        RefreshToken saved = tokens.save(RefreshToken.issue(
                user.getId(), familyId, opaqueTokens.hash(refreshToken), user.getTokenVersion(), now, expiresAt));
        String accessToken = accessTokens.generateToken(user, familyId);
        return new Stored(new AuthSession(accessToken, refreshToken, accessTokens.getAccessTtlSeconds(), user), saved);
    }

    /** A freshly issued session together with the stored token row behind its refresh token. */
    private record Stored(AuthSession session, RefreshToken token) {}

    private void enforceSessionLimit(String userId, Instant now) {
        List<String> live = tokens.activeFamiliesOf(userId, now);
        for (int i = 0; i < live.size() - maxSessionsPerUser; i++) {
            tokens.revokeFamily(live.get(i), REASON_SESSION_LIMIT, now);
        }
    }
}
