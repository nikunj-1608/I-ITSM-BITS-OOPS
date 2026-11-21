package com.iitsm.application.service;

import com.iitsm.application.port.TokenPort;
import com.iitsm.domain.exception.InvalidMfaCodeException;
import com.iitsm.domain.model.User;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Sign-in as a session (ADR-047, ADR-048): verifies the credentials; an account with two-step verification gets a
 * challenge token and must send a code, any other account gets a session of an access token and a refresh token.
 *
 * <p>Deliberately carries no {@code @Transactional}: the password check must not hold a database connection
 * ({@link UserService#verifyCredentials}, ADR-033), and the services it calls open their own short transactions
 * afterwards. {@code LoginTransactionContractTest} pins this.
 */
@Service
public class AuthSessionService {

    private final UserService userService;
    private final RefreshTokenService refreshTokenService;
    private final MfaService mfaService;
    private final TokenPort tokenPort;

    public AuthSessionService(
            UserService userService,
            RefreshTokenService refreshTokenService,
            MfaService mfaService,
            TokenPort tokenPort) {
        this.userService = Objects.requireNonNull(userService, "userService cannot be null");
        this.refreshTokenService = Objects.requireNonNull(refreshTokenService, "refreshTokenService cannot be null");
        this.mfaService = Objects.requireNonNull(mfaService, "mfaService cannot be null");
        this.tokenPort = Objects.requireNonNull(tokenPort, "tokenPort cannot be null");
    }

    public LoginResult login(String email, String rawPassword) {
        User user = userService.verifyCredentials(email, rawPassword);
        if (user.isMfaEnabled()) {
            return new LoginResult.MfaRequired(tokenPort.generateMfaChallenge(user));
        }
        return new LoginResult.Authenticated(refreshTokenService.issueSession(user));
    }

    /** The second step: the challenge from {@link #login} and a one-time code or recovery code. */
    public AuthSession verifyMfa(String challengeToken, String code) {
        String userId = tokenPort.parseMfaChallenge(challengeToken).orElseThrow(InvalidMfaCodeException::new);
        User user = mfaService.verifyChallenge(userId, code);
        return refreshTokenService.issueSession(user);
    }
}
