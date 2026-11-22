package com.iitsm.infrastructure.security;

import com.iitsm.domain.policy.RateLimitDecision;
import com.iitsm.domain.policy.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

// OOP (Inheritance / Realization): InMemoryRateLimiter extends or implements a framework or port type, so it plugs into the framework without changing callers.
/**
 * Pattern: Strategy (adapter of the {@link RateLimiter} port). A token bucket per key, in memory (ADR-046).
 *
 * <p>Each key may make {@code capacity} attempts at once; the bucket refills continuously at {@code capacity} tokens
 * per {@code window}, so a client that keeps trying gets one attempt every {@code window / capacity}. A bucket is
 * changed only inside {@link ConcurrentHashMap#compute}, so concurrent attempts on one key never take the same token.
 *
 * <p>Memory is bounded: when more than {@code maxTrackedKeys} keys are tracked, the buckets that have refilled
 * completely are dropped (a full bucket answers exactly like a missing one, so nothing is lost), and if that is not
 * enough the least recently used ones are dropped until 90 % of the limit is left. Under contention the map can
 * briefly hold a few keys more than the limit (one purge runs at a time; others do not wait for it).
 */
public final class InMemoryRateLimiter implements RateLimiter {

    private static final double ONE_TOKEN = 1.0;
    /** Rounding slack: refill sums in floating point may land a hair under a whole token exactly on time. */
    private static final double TOKEN_EPSILON = 1e-9;
    private static final double PURGE_TARGET_RATIO = 0.9;

    private final double capacity;
    private final Duration window;
    private final long windowNanos;
    private final int maxTrackedKeys;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong touches = new AtomicLong();
    private final ReentrantLock purgeLock = new ReentrantLock();

    public InMemoryRateLimiter(int capacity, Duration window, int maxTrackedKeys) {
        Objects.requireNonNull(window, "window cannot be null");
        if (capacity <= 0 || window.isZero() || window.isNegative() || maxTrackedKeys <= 0) {
            throw new IllegalArgumentException("capacity, window and maxTrackedKeys must be positive");
        }
        this.capacity = capacity;
        this.window = window;
        this.windowNanos = window.toNanos();
        this.maxTrackedKeys = maxTrackedKeys;
    }

    @Override
    public RateLimitDecision tryAcquire(String key, Instant now) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(now, "now cannot be null");
        RateLimitDecision[] decision = new RateLimitDecision[1];
        buckets.compute(key, (k, bucket) -> {
            double tokens = available(bucket, now);
            Instant stamp = bucket == null || now.isAfter(bucket.updatedAt()) ? now : bucket.updatedAt();
            long touch = touches.incrementAndGet();
            if (tokens >= ONE_TOKEN - TOKEN_EPSILON) {
                decision[0] = RateLimitDecision.allow();
                return new Bucket(Math.max(0, tokens - ONE_TOKEN), stamp, touch);
            }
            decision[0] = RateLimitDecision.refuse(timeUntilOneToken(tokens));
            return new Bucket(tokens, stamp, touch);
        });
        if (buckets.size() > maxTrackedKeys) {
            purge(now);
        }
        return decision[0];
    }

    /** Number of keys currently tracked (for tests and diagnostics). */
    int trackedKeys() {
        return buckets.size();
    }

    private double available(Bucket bucket, Instant now) {
        if (bucket == null) {
            return capacity;
        }
        Duration elapsed = Duration.between(bucket.updatedAt(), now);
        if (elapsed.isNegative() || elapsed.isZero()) {
            return bucket.tokens();
        }
        if (elapsed.compareTo(window) >= 0) {
            return capacity;
        }
        double refill = (double) elapsed.toNanos() * capacity / windowNanos;
        return Math.min(capacity, bucket.tokens() + refill);
    }

    private Duration timeUntilOneToken(double tokens) {
        double missing = ONE_TOKEN - tokens;
        long nanos = (long) Math.ceil(missing * windowNanos / capacity);
        return Duration.ofNanos(Math.max(1, nanos));
    }

    private void purge(Instant now) {
        if (!purgeLock.tryLock()) {
            return;
        }
        try {
            buckets.forEach((key, bucket) -> {
                if (available(bucket, now) >= capacity - TOKEN_EPSILON) {
                    buckets.remove(key, bucket);
                }
            });
            evictLeastRecentlyUsed();
        } finally {
            purgeLock.unlock();
        }
    }

    private void evictLeastRecentlyUsed() {
        int target = (int) (maxTrackedKeys * PURGE_TARGET_RATIO);
        int excess = buckets.size() - target;
        if (buckets.size() <= maxTrackedKeys || excess <= 0) {
            return;
        }
        buckets.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().touch()))
                .limit(excess)
                .map(Map.Entry::copyOf)
                .toList()
                .forEach(entry -> buckets.remove(entry.getKey(), entry.getValue()));
    }

    /** Tokens left at {@code updatedAt}; {@code touch} orders buckets by last use. */
    private record Bucket(double tokens, Instant updatedAt, long touch) {}
}
