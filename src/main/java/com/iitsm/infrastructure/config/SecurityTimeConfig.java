package com.iitsm.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock for security decisions (authentication hardening, rule R1, ADR-046).
 *
 * <p>The application's primary {@link Clock} is the simulation {@link AdjustableClock}, which
 * {@code /api/simulation/advance} can move by up to ten years. Token expiry, refresh expiry, rate-limit refill and
 * one-time-code time steps must not follow it, or one simulation scenario would sign everybody out or refill every
 * rate limit. Security classes inject this bean with {@code @Qualifier(SecurityTimeConfig.WALL_CLOCK)}; the account
 * lockout keeps the shared clock (ADR-033), so the simulation can still demonstrate a lock that expires.
 */
@Configuration
public class SecurityTimeConfig {

    public static final String WALL_CLOCK = "wallClock";

    @Bean(WALL_CLOCK)
    public Clock wallClock() {
        return Clock.systemUTC();
    }
}
