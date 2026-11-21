package com.iitsm.web.controller;

import com.iitsm.application.service.AuthSession;
import com.iitsm.application.service.AuthSessionService;
import com.iitsm.application.service.LoginResult;
import com.iitsm.application.service.MfaService;
import com.iitsm.application.service.RefreshTokenService;
import com.iitsm.application.service.UserService;
import com.iitsm.domain.model.Designation;
import com.iitsm.domain.model.User;
import com.iitsm.web.dto.AuthResponse;
import com.iitsm.web.dto.LoginRequest;
import com.iitsm.web.dto.LogoutRequest;
import com.iitsm.web.dto.MfaCodeRequest;
import com.iitsm.web.dto.MfaDisableRequest;
import com.iitsm.web.dto.MfaRecoveryCodesResponse;
import com.iitsm.web.dto.MfaSetupResponse;
import com.iitsm.web.dto.MfaVerifyRequest;
import com.iitsm.web.dto.RefreshRequest;
import com.iitsm.web.dto.RegisterRequest;
import com.iitsm.web.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller managing authentication, sessions (short access token, rotating refresh token, sign-out; ADR-047),
 * optional two-step verification for administrators (ADR-048) and employee self-registration. Zero business logic: it
 * validates the DTO, calls one service method and maps the result.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final AuthSessionService authSessionService;
    private final RefreshTokenService refreshTokenService;
    private final MfaService mfaService;
    private final CurrentUser currentUser;

    public AuthController(
            UserService userService,
            AuthSessionService authSessionService,
            RefreshTokenService refreshTokenService,
            MfaService mfaService,
            CurrentUser currentUser) {
        this.userService = Objects.requireNonNull(userService, "userService cannot be null");
        this.authSessionService = Objects.requireNonNull(authSessionService, "authSessionService cannot be null");
        this.refreshTokenService = Objects.requireNonNull(refreshTokenService, "refreshTokenService cannot be null");
        this.mfaService = Objects.requireNonNull(mfaService, "mfaService cannot be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser cannot be null");
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(toResponse(authSessionService.login(request.email(), request.password())));
    }

    /** The second sign-in step for an account with two-step verification (ADR-048). */
    @PostMapping("/mfa/verify")
    public ResponseEntity<AuthResponse> verifyMfa(@Valid @RequestBody MfaVerifyRequest request) {
        return ResponseEntity.ok(toResponse(authSessionService.verifyMfa(request.mfaToken(), request.code())));
    }

    /** Starts the two-step verification setup for the signed-in administrator; the secret is shown this once. */
    @PostMapping("/mfa/setup")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MfaSetupResponse> setupMfa(Authentication authentication) {
        MfaService.MfaSetup setup = mfaService.beginSetup(currentUser.idOf(authentication));
        return ResponseEntity.ok(new MfaSetupResponse(setup.secret(), setup.otpauthUri()));
    }

    /** Confirms the setup with the first code and returns the recovery codes, shown this once. */
    @PostMapping("/mfa/enable")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MfaRecoveryCodesResponse> enableMfa(
            Authentication authentication, @Valid @RequestBody MfaCodeRequest request) {
        return ResponseEntity.ok(new MfaRecoveryCodesResponse(
                mfaService.confirmSetup(currentUser.idOf(authentication), request.code())));
    }

    @PostMapping("/mfa/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> disableMfa(
            Authentication authentication, @Valid @RequestBody MfaDisableRequest request) {
        mfaService.disable(currentUser.idOf(authentication), request.password(), request.code());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        // Finding S3: Self-registration ignores client-supplied designation to prevent privilege escalation
        Designation designation = Designation.ASSOCIATE;

        String dept = (request.department() != null && !request.department().isBlank())
                ? request.department().trim()
                : "Engineering";

        userService.registerEmployee(
                request.name(),
                request.email(),
                request.password(),
                designation,
                dept,
                1);

        return ResponseEntity.ok(toResponse(authSessionService.login(request.email(), request.password())));
    }

    /** Renews the session: a new access token and the next refresh token; the presented refresh token is used up. */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(toResponse(refreshTokenService.refresh(request.refreshToken())));
    }

    /** Ends the session of the given refresh token. Always 204 for a well-formed request (idempotent). */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        refreshTokenService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** Ends every session of the signed-in user and every access token issued before. Needs a valid access token. */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(Authentication authentication) {
        refreshTokenService.logoutAll(currentUser.idOf(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> getCurrentUser(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(401).build();
        }
        String email = authentication.getName();
        User user = userService.findByEmail(email).orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).build();
        }
        return ResponseEntity.ok(new AuthResponse(
                null,
                user.getId(),
                user.getName(),
                user.getEmail().value(),
                user.getRole().name(),
                null,
                null,
                null,
                null,
                user.isMfaEnabled()));
    }

    private static AuthResponse toResponse(LoginResult result) {
        if (result instanceof LoginResult.MfaRequired required) {
            return AuthResponse.mfaChallenge(required.challengeToken());
        }
        return toResponse(((LoginResult.Authenticated) result).session());
    }

    private static AuthResponse toResponse(AuthSession session) {
        User user = session.user();
        return new AuthResponse(
                session.accessToken(),
                user.getId(),
                user.getName(),
                user.getEmail().value(),
                user.getRole().name(),
                session.refreshToken(),
                session.expiresInSeconds());
    }
}
