package com.iitsm.application.service;

import com.iitsm.domain.model.User;

/**
 * What a sign-in or a refresh hands back (ADR-047): a short-lived access token, the refresh token for the next
 * renewal (shown once; only its hash is stored), the access token's lifetime in seconds and the user it belongs to.
 */
public record AuthSession(String accessToken, String refreshToken, long expiresInSeconds, User user) {}
