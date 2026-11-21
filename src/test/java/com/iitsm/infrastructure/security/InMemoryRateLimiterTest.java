package com.iitsm.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.policy.RateLimitDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Authentication hardening, Phase S2 (ADR-046): the token bucket. Every test passes its own instants, so nothing
 * depends on the real time.
 */
class InMemoryRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final Duration MINUTE = Duration.ofSeconds(60);

    private static int allowedCount(InMemoryRateLimiter limiter, String key, Instant at, int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryAcquire(key, at).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    @Test
    @DisplayName("a burst of capacity attempts is allowed, the next one is refused")
    void burstThenRefuse() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(3, MINUTE, 100);

        assertThat(allowedCount(limiter, "login:1.2.3.4", T0, 3)).isEqualTo(3);
        assertThat(limiter.tryAcquire("login:1.2.3.4", T0).allowed()).isFalse();
    }

    @Test
    @DisplayName("the bucket refills at capacity per window: one token after window / capacity, not before")
    void refillIsExact() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(3, MINUTE, 100);
        allowedCount(limiter, "k", T0, 3);

        assertThat(limiter.tryAcquire("k", T0.plusSeconds(19)).allowed()).isFalse();
        assertThat(limiter.tryAcquire("k", T0.plusSeconds(20)).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", T0.plusSeconds(20)).allowed()).isFalse();
    }

    @Test
    @DisplayName("a whole window refills the bucket to capacity and no further")
    void refillStopsAtCapacity() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(3, MINUTE, 100);
        allowedCount(limiter, "k", T0, 3);

        assertThat(allowedCount(limiter, "k", T0.plus(Duration.ofDays(365)), 10)).isEqualTo(3);
    }

    @Test
    @DisplayName("the retry time is the time until one token is back, rounded up to whole seconds")
    void retryAfterIsTimeToNextToken() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(3, MINUTE, 100);
        allowedCount(limiter, "k", T0, 3);

        RateLimitDecision now = limiter.tryAcquire("k", T0);
        RateLimitDecision later = limiter.tryAcquire("k", T0.plusMillis(5_500));

        assertThat(now.retryAfter()).isEqualTo(Duration.ofSeconds(20));
        assertThat(now.retryAfterSeconds()).isEqualTo(20);
        assertThat(later.retryAfter()).isBetween(Duration.ofMillis(14_500), Duration.ofMillis(14_501));
        assertThat(later.retryAfterSeconds()).isEqualTo(15);
    }

    @Test
    @DisplayName("a clock that goes back gives no extra tokens")
    void clockGoingBackGivesNothing() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(1, MINUTE, 100);
        assertThat(limiter.tryAcquire("k", T0).allowed()).isTrue();

        assertThat(limiter.tryAcquire("k", T0.minusSeconds(3600)).allowed()).isFalse();
        assertThat(limiter.tryAcquire("k", T0.plusSeconds(59)).allowed()).isFalse();
        assertThat(limiter.tryAcquire("k", T0.plusSeconds(60)).allowed()).isTrue();
    }

    @Test
    @DisplayName("keys are independent")
    void keysAreIndependent() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(2, MINUTE, 100);
        allowedCount(limiter, "login:10.0.0.1", T0, 5);

        assertThat(allowedCount(limiter, "login:10.0.0.2", T0, 5)).isEqualTo(2);
        assertThat(allowedCount(limiter, "register:10.0.0.1", T0, 5)).isEqualTo(2);
    }

    @Test
    @DisplayName("memory is bounded: 50,000 different keys never leave more than the limit tracked")
    void memoryIsBounded() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(5, MINUTE, 1000);

        for (int i = 0; i < 50_000; i++) {
            limiter.tryAcquire("login:key-" + i, T0);
            assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(1000);
        }
    }

    @Test
    @DisplayName("purging drops refilled buckets first and keeps a recently refused client refused")
    void purgeKeepsActiveClients() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(1, MINUTE, 10);
        allowedCount(limiter, "attacker", T0, 2);
        for (int i = 0; i < 9; i++) {
            limiter.tryAcquire("old-" + i, T0.minusSeconds(120));
        }
        // every old bucket has refilled by T0 + 1 s, so the purge removes them, not the attacker
        limiter.tryAcquire("newcomer", T0.plusSeconds(1));

        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(10);
        assertThat(limiter.tryAcquire("attacker", T0.plusSeconds(1)).allowed()).isFalse();
    }

    @Test
    @DisplayName("200 threads on one key with capacity 10 get exactly 10 tokens")
    void concurrentCallersShareTheBucket() throws Exception {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(10, MINUTE, 100);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return limiter.tryAcquire("login:203.0.113.9", T0).allowed();
            }));
        }
        start.countDown();
        int allowed = 0;
        for (Future<Boolean> result : results) {
            allowed += result.get(10, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();

        assertThat(allowed).isEqualTo(10);
    }

    @Test
    @DisplayName("capacity, window and the key limit must be positive")
    void rejectsNonPositiveSettings() {
        assertThatThrownBy(() -> new InMemoryRateLimiter(0, MINUTE, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryRateLimiter(1, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryRateLimiter(1, MINUTE, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a decision is either allowed with no wait or refused with a positive wait")
    void decisionInvariants() {
        assertThat(RateLimitDecision.allow().retryAfterSeconds()).isZero();
        assertThat(RateLimitDecision.refuse(Duration.ofMillis(1)).retryAfterSeconds()).isEqualTo(1);
        assertThatThrownBy(() -> RateLimitDecision.refuse(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitDecision(true, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
