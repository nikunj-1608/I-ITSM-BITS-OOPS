package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.application.port.TokenPort;
import com.iitsm.domain.exception.InvalidCredentialsException;
import com.iitsm.domain.model.Designation;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Employee;
import com.iitsm.domain.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authentication hardening, Phase S3 (ADR-047): sign-in is "verify the credentials, then start a session". */
class AuthSessionServiceTest {

    private final UserService userService = mock(UserService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final MfaService mfaService = mock(MfaService.class);
    private final TokenPort tokenPort = mock(TokenPort.class);
    private final AuthSessionService service = new AuthSessionService(userService, refreshTokens, mfaService, tokenPort);

    private static Employee employee() {
        return new Employee("EMP-1", "Jane Doe", new Email("jane@nimbusfinserve.com"), "hash",
                Designation.ASSOCIATE, "Engineering", 1);
    }

    @Test
    @DisplayName("verified credentials start a session for that user and return it unchanged")
    void startsASession() {
        User user = employee();
        AuthSession expected = new AuthSession("access", "refresh", 900, user);
        when(userService.verifyCredentials("jane@nimbusfinserve.com", "Password123!")).thenReturn(user);
        when(refreshTokens.issueSession(user)).thenReturn(expected);

        LoginResult result = service.login("jane@nimbusfinserve.com", "Password123!");

        assertThat(result).isEqualTo(new LoginResult.Authenticated(expected));
    }

    @Test
    @DisplayName("failed credentials start no session")
    void noSessionOnFailure() {
        when(userService.verifyCredentials("jane@nimbusfinserve.com", "wrong"))
                .thenThrow(new InvalidCredentialsException("Invalid credentials"));

        assertThatThrownBy(() -> service.login("jane@nimbusfinserve.com", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(refreshTokens, never()).issueSession(org.mockito.ArgumentMatchers.any());
    }
}
