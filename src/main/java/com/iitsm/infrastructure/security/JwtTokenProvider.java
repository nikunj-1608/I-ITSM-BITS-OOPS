package com.iitsm.infrastructure.security;

import com.iitsm.application.port.TokenPort;
import com.iitsm.domain.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Service responsible for generating and validating stateless JSON Web Tokens (JWT).
 * Enforces production security checks: minimum 256-bit key length and blocks committed secret in prod.
 *
 * <p>Issue and expiry times come from the real wall clock (rule R1, ADR-047), never from the simulation clock, which
 * the simulation can move by years. An access token carries the {@code sid} claim (the session it belongs to) when it
 * was issued for a session; {@code JwtAuthenticationFilter} rejects it as soon as that session is revoked.
 */
@Component
public class JwtTokenProvider implements TokenPort {

    public static final String COMMITTED_DEFAULT_SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    static final String SESSION_CLAIM = "sid";
    static final String PURPOSE_CLAIM = "purpose";
    static final String MFA_PURPOSE = "mfa";
    private static final long MILLIS_PER_SECOND = 1000;
    private static final long MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND;

    private final SecretKey key;
    private final long expirationMs;
    private final Clock wallClock;
    private final long challengeMs;

    public JwtTokenProvider(
            @Value("${iitsm.security.jwt.secret:}") String secret,
            @Value("${iitsm.security.jwt.expiration-ms:900000}") long expirationMs,
            Environment environment,
            @Qualifier("wallClock") Clock wallClock,
            @Value("${iitsm.security.mfa.challenge-minutes:5}") long challengeMinutes) {

        Objects.requireNonNull(environment, "environment cannot be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock cannot be null");

        if (secret == null || secret.trim().isEmpty() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes (256 bits). Configure iitsm.security.jwt.secret.");
        }

        if (environment.matchesProfiles("prod") && COMMITTED_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException("Production environment cannot use the committed default JWT secret! Set a secure JWT_SECRET environment variable.");
        }

        if (expirationMs <= 0) {
            throw new IllegalStateException("iitsm.security.jwt.expiration-ms must be positive");
        }
        if (challengeMinutes <= 0) {
            throw new IllegalStateException("iitsm.security.mfa.challenge-minutes must be positive");
        }
        this.challengeMs = challengeMinutes * MILLIS_PER_MINUTE;

        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    @Override
    public String generateToken(User user) {
        return generateToken(user, null);
    }

    @Override
    public String generateToken(User user, String sessionId) {
        Date now = Date.from(wallClock.instant());
        Date expiryDate = new Date(now.getTime() + expirationMs);
        int version = (user != null) ? user.getTokenVersion() : 1;

        var builder = Jwts.builder()
                .subject(user.getEmail().value())
                .claim("userId", user.getId())
                .claim("role", user.getRole().name())
                .claim("name", user.getName())
                .claim("tokenVersion", version);
        if (sessionId != null) {
            builder.claim(SESSION_CLAIM, sessionId);
        }
        return builder.issuedAt(now).expiration(expiryDate).signWith(key).compact();
    }

    @Override
    public String generateMfaChallenge(User user) {
        Date now = Date.from(wallClock.instant());
        return Jwts.builder()
                .subject(user.getEmail().value())
                .claim("userId", user.getId())
                .claim(PURPOSE_CLAIM, MFA_PURPOSE)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + challengeMs))
                .signWith(key)
                .compact();
    }

    @Override
    public Optional<String> parseMfaChallenge(String token) {
        try {
            Claims claims = claimsOf(token);
            return MFA_PURPOSE.equals(claims.get(PURPOSE_CLAIM, String.class))
                    ? Optional.ofNullable(claims.get("userId", String.class))
                    : Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** True for a two-step verification challenge: such a token proves the password step only and is no credential. */
    public boolean isMfaChallenge(String token) {
        return MFA_PURPOSE.equals(claimsOf(token).get(PURPOSE_CLAIM, String.class));
    }

    @Override
    public String getSessionId(String token) {
        return claimsOf(token).get(SESSION_CLAIM, String.class);
    }

    @Override
    public long getAccessTtlSeconds() {
        return expirationMs / MILLIS_PER_SECOND;
    }

    public int getTokenVersionFromToken(String token) {
        Integer v = claimsOf(token).get("tokenVersion", Integer.class);
        return (v != null) ? v : 1;
    }

    @Override
    public String getEmailFromToken(String token) {
        return claimsOf(token).getSubject();
    }

    @Override
    public boolean validateToken(String token) {
        try {
            claimsOf(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Verifies the signature and the expiry against the wall clock, and returns the claims. */
    private Claims claimsOf(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(wallClock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
