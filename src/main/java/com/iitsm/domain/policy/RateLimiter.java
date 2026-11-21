package com.iitsm.domain.policy;

import java.time.Instant;

/**
 * Pattern: Strategy (port). Decides whether one more attempt under {@code key} is allowed at {@code now}, and takes
 * it when it is. The key names what is limited (for the sign-in endpoints: the route and the client address,
 * ADR-046). The caller passes the time, so an implementation never reads a clock of its own and tests pass fixed
 * instants.
 */
public interface RateLimiter {

    RateLimitDecision tryAcquire(String key, Instant now);
}
