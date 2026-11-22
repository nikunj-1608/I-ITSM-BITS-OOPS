package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.application.port.TokenPort;
import com.iitsm.domain.exception.InvalidMfaCodeException;
import com.iitsm.domain.model.AdminLevel;
import com.iitsm.domain.model.Administrator;
import com.iitsm.domain.model.Email;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authentication hardening, Phase S4 (ADR-048): the two steps of signing in to an account with two-step verification. */
class AuthSessionServiceMfaTest {

    private final UserService userService = mock(UserService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final MfaService mfaService = mock(MfaService.class);
    private final TokenPort tokenPort = mock(TokenPort.class);
    private final AuthSessionService service = new AuthSessionService(userService, refreshTokens, mfaService, tokenPort);
    private final Administrator admin = new Administrator("ADM-1", "Ada Admin", new Email("ada@nimbusfinserve.com"),
            "hash", AdminLevel.SYSTEM_ADMIN);

    @Test
    @DisplayName("with two-step verification the password step returns a challenge and issues no session")
    void passwordStepGivesAChallenge() {
        admin.beginMfaEnrollment("encrypted");
        admin.enableMfa();
        when(userService.verifyCredentials("ada@nimbusfinserve.com", "pw")).thenReturn(admin);
        when(tokenPort.generateMfaChallenge(admin)).thenReturn("challenge-token");

        LoginResult result = service.login("ada@nimbusfinserve.com", "pw");

        assertThat(result).isEqualTo(new LoginResult.MfaRequired("challenge-token"));
        verify(refreshTokens, never()).issueSession(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("without two-step verification the password step starts the session and issues no challenge")
    void noChallengeWithoutMfa() {
        AuthSession session = new AuthSession("access", "refresh", 900, admin);
        when(userService.verifyCredentials("ada@nimbusfinserve.com", "pw")).thenReturn(admin);
        when(refreshTokens.issueSession(admin)).thenReturn(session);

        assertThat(service.login("ada@nimbusfinserve.com", "pw")).isEqualTo(new LoginResult.Authenticated(session));
        verify(tokenPort, never()).generateMfaChallenge(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a valid challenge and a good code start the session")
    void secondStep() {
        AuthSession session = new AuthSession("access", "refresh", 900, admin);
        when(tokenPort.parseMfaChallenge("challenge-token")).thenReturn(Optional.of("ADM-1"));
        when(mfaService.verifyChallenge("ADM-1", "123456")).thenReturn(admin);
        when(refreshTokens.issueSession(admin)).thenReturn(session);

        assertThat(service.verifyMfa("challenge-token", "123456")).isSameAs(session);
    }

    @Test
    @DisplayName("an invalid or expired challenge, or an access token used as a challenge, is refused before any code is checked")
    void invalidChallenge() {
        when(tokenPort.parseMfaChallenge("not-a-challenge")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyMfa("not-a-challenge", "123456")).isInstanceOf(InvalidMfaCodeException.class);

        verify(mfaService, never()).verifyChallenge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(refreshTokens, never()).issueSession(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a refused code starts no session")
    void refusedCode() {
        when(tokenPort.parseMfaChallenge("challenge-token")).thenReturn(Optional.of("ADM-1"));
        when(mfaService.verifyChallenge("ADM-1", "000000")).thenThrow(new InvalidMfaCodeException());

        assertThatThrownBy(() -> service.verifyMfa("challenge-token", "000000")).isInstanceOf(InvalidMfaCodeException.class);

        verify(refreshTokens, never()).issueSession(org.mockito.ArgumentMatchers.any());
    }
}
