package com.iitsm.domain.policy;

import java.time.Duration;
import java.util.Objects;

/**
 * OOP: Value object. The answer of a {@link RateLimiter}: the attempt is allowed, or it is refused and the next
 * attempt can succeed after {@link #retryAfter()} (ADR-046).
 */
public record RateLimitDecision(boolean allowed, Duration retryAfter) {

    private static final RateLimitDecision ALLOWED = new RateLimitDecision(true, Duration.ZERO);
    private static final long MIN_RETRY_SECONDS = 1;

    public RateLimitDecision {
        Objects.requireNonNull(retryAfter, "retryAfter cannot be null");
        if (allowed && !retryAfter.isZero()) {
            throw new IllegalArgumentException("An allowed attempt has no retry time");
        }
        if (!allowed && (retryAfter.isZero() || retryAfter.isNegative())) {
            throw new IllegalArgumentException("A refused attempt needs a positive retry time");
        }
    }

    public static RateLimitDecision allow() {
        return ALLOWED;
    }

    public static RateLimitDecision refuse(Duration retryAfter) {
        return new RateLimitDecision(false, retryAfter);
    }

    /** The retry time in whole seconds, rounded up (an HTTP {@code Retry-After} value); 0 when allowed. */
    public long retryAfterSeconds() {
        if (allowed) {
            return 0;
        }
        long seconds = retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0);
        return Math.max(MIN_RETRY_SECONDS, seconds);
    }
}
