package com.iitsm.infrastructure.scheduling;

import com.iitsm.domain.repository.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes refresh tokens that expired more than {@code purge-after-days} ago (ADR-047). A token is kept for a while
 * after it expires so that a replay of an old rotated token is still recognised as a replay. A failure is logged and the
 * next run tries again; it never stops the scheduler.
 */
@Component
public class RefreshTokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupScheduler.class);

    private final RefreshTokenRepository tokens;
    private final Clock wallClock;
    private final Duration keepExpiredFor;

    public RefreshTokenCleanupScheduler(
            RefreshTokenRepository tokens,
            @Qualifier("wallClock") Clock wallClock,
            @Value("${iitsm.security.refresh.purge-after-days:7}") long purgeAfterDays) {
        this.tokens = Objects.requireNonNull(tokens, "tokens cannot be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock cannot be null");
        if (purgeAfterDays < 0) {
            throw new IllegalStateException("iitsm.security.refresh.purge-after-days must not be negative");
        }
        this.keepExpiredFor = Duration.ofDays(purgeAfterDays);
    }

    @Scheduled(fixedDelayString = "${iitsm.security.refresh.cleanup-interval-ms:3600000}")
    public void purgeExpired() {
        try {
            int deleted = tokens.deleteExpiredBefore(wallClock.instant().minus(keepExpiredFor));
            log.debug("Refresh token cleanup: {} expired token(s) deleted", deleted);
        } catch (RuntimeException e) {
            log.warn("Refresh token cleanup failed; it will be retried: {}", e.getMessage());
        }
    }
}
