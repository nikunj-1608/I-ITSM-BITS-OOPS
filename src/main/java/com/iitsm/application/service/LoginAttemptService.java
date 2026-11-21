package com.iitsm.application.service;

import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service managing failed login attempt counting and account lockout.
 *
 * <p>Every method is an ordinary transaction that starts when it is called. {@code UserService.authenticate} runs
 * without a transaction, so the counter is committed before the "invalid credentials" exception is thrown, and no login
 * holds one connection while it asks for a second (ADR-033, stress-audit item P1: with as many simultaneous logins as
 * pool connections the nested {@code REQUIRES_NEW} transaction starved the pool).
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final UserRepository userRepository;
    private final Clock clock;
    private final int maxFailedLogins;
    private final Duration lockDuration;

    public LoginAttemptService(
            UserRepository userRepository,
            Clock clock,
            @Value("${iitsm.security.max-failed-logins:5}") int maxFailedLogins,
            @Value("${iitsm.security.lockout-minutes:15}") long lockoutMinutes) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.maxFailedLogins = maxFailedLogins;
        this.lockDuration = Duration.ofMinutes(lockoutMinutes);
    }

    @Transactional
    public void recordFailedAttempt(String emailStr) {
        if (emailStr == null || emailStr.isBlank()) {
            return;
        }
        try {
            Email email = new Email(emailStr);
            userRepository.findByEmail(email).ifPresent(user -> {
                java.time.Instant now = clock.instant();
                user.recordFailedLogin(maxFailedLogins, now, lockDuration);
                userRepository.save(user);
                log.warn("Recorded failed login attempt for '{}'. Total attempts: {}, Locked: {}",
                        emailStr, user.getFailedLoginAttempts(), user.isCurrentlyLocked(now));
            });
        } catch (Exception e) {
            log.warn("Could not record failed login attempt for '{}': {}", emailStr, e.getMessage());
        }
    }

    @Transactional
    public void resetFailedAttempts(String emailStr) {
        if (emailStr == null || emailStr.isBlank()) {
            return;
        }
        try {
            Email email = new Email(emailStr);
            userRepository.findByEmail(email).ifPresent(user -> {
                if (user.getFailedLoginAttempts() > 0) {
                    user.resetFailedLogins();
                    userRepository.save(user);
                    log.info("Reset failed login attempts for '{}'", emailStr);
                }
            });
        } catch (Exception e) {
            log.warn("Could not reset failed logins for '{}': {}", emailStr, e.getMessage());
        }
    }

    @Transactional
    public void unlockUser(String userId) {
        Objects.requireNonNull(userId, "userId cannot be null");
        userRepository.findById(userId).ifPresent(user -> {
            user.resetFailedLogins();
            user.setLocked(false);
            userRepository.save(user);
            log.info("Admin unlocked user account '{}'", userId);
        });
    }
}

