# Security Considerations - I-ITSM

Written for the CS F213 course evaluators. It explains how I-ITSM (the incident management system of the fictional
fintech Nimbus FinServe Inc.) covers each security item the project specification lists. Every statement below was
checked in the code on 2026-11-19 (branch `bug-fixes`, after the authentication hardening phases S1 to S4). File
references are relative to `src/main/java/com/iitsm/` unless they start with `src/` or `docs/`; they name the file and
the class or method, because line numbers move.

The last sections give a threat table, the configuration reference, the hardening that was added (rate limiting, refresh
tokens with revocation, optional two-step verification), and the gaps we still know about. The design decisions are in
`docs/DECISIONS.md` (ADR-033, ADR-046, ADR-047, ADR-048) and the story of the work in `docs/auth-hardening-summary.md`.

---

## Authentication

**How a user signs in.**
1. `POST /api/auth/login` takes `{email, password}`. The request is validated first: both fields are required and the
   e-mail must be well formed (`web/dto/LoginRequest.java`).
2. `UserService.verifyCredentials` (`application/service/UserService.java`) looks the user up and refuses the login if
   the user is unknown, deactivated, currently locked or gives the wrong password. **All four cases return the same
   HTTP 401 with the message "Invalid credentials"** (`InvalidCredentialsException`, mapped in
   `GlobalExceptionHandler.resolveStatus`), so an attacker cannot tell which e-mail addresses exist.
3. `AuthSessionService.login` then either starts a **session** (an access token and a refresh token, below) or, for an
   administrator who turned on two-step verification, returns a short-lived challenge and no tokens (see "Two-step
   verification"). The answer carries the user's id, name, e-mail and role (`web/controller/AuthController.java`).

**The access token.** `infrastructure/security/JwtTokenProvider.java`:
- A signed **JSON Web Token**, HMAC-SHA with a secret of at least 32 bytes (256 bits). A shorter or missing secret stops
  the application at start-up. The prod profile has no fallback secret (`src/main/resources/application.yml`, prod block),
  so a production start without `JWT_SECRET` fails instead of running with a known key.
- Claims: subject = e-mail, `userId`, `role`, `name`, `tokenVersion`, `sid` (the session it belongs to), issue time and
  expiry.
- **Lifetime 15 minutes in every profile** (`iitsm.security.jwt.expiration-ms` = 900000; the web app renews it silently,
  so no profile needs a longer one; `RefreshConfigContractTest` pins every profile at 15 minutes or less).
- Time comes from the **real wall clock** (`SecurityTimeConfig`, bean `wallClock`), never from the simulation clock: the
  simulation can move the shared clock by years, and one scenario must not sign everybody out or give a token a longer
  life (rule R1; `SimulationClockDoesNotExpireSessionsRegressionTest`).

**Sessions with a rotating refresh token (ADR-047).** `application/service/RefreshTokenService.java`:
- A sign-in starts a session family. The browser holds the access token and a **refresh token**: 32 random bytes from
  `SecureRandom`, Base64url (`infrastructure/security/SecureOpaqueTokenGenerator.java`). The database stores only its
  **SHA-256 hash** (`refresh_tokens.token_hash`, migration V12), never the token.
- `POST /api/auth/refresh` exchanges a refresh token for a new access token and the **next** refresh token; the presented
  one is used up (rotation). The session ends 8 hours after sign-in, whatever the activity (`iitsm.security.refresh.ttl-hours`).
- **Replay detection**: presenting a refresh token that was already rotated (`RefreshTokenRotation.REUSED`) revokes the
  whole family, the newest token and every access token of the session included. Two simultaneous refreshes of one token
  end as one success and one 401 (a single conditional `UPDATE`).
- Refresh also fails, and ends the session, when the user is deactivated, locked, or their token version changed.
- Every failure is the same `InvalidRefreshTokenException` (HTTP 401 `ERR_INVALID_REFRESH_TOKEN`, one message), so
  unknown, expired, revoked and replayed tokens cannot be told apart.
- A user may hold at most 10 live sessions (`max-sessions-per-user`); the 11th sign-in ends the least recently used one.

**Revocation takes effect at once.** `infrastructure/security/JwtAuthenticationFilter.java` verifies the signature and
expiry, reloads the user from the database on every request, and answers 401 when:
- the account is deactivated or currently locked, so locking or deactivating a user takes effect on their very next
  request, not when the token expires;
- the `tokenVersion` claim differs from the user's stored version (`User.bumpTokenVersion`, raised by "sign out
  everywhere");
- the token's `sid` names a session that is revoked or expired (`RefreshTokenRepository.isFamilyActive`): sign-out, a
  replay, a reset of two-step verification and the end of the 8 hours all kill the access token immediately;
- the token is malformed, forged or expired, or is a two-step verification challenge (never a credential).
A token without `sid` (issued by older code) is accepted until it expires.

**Signing out.** `POST /api/auth/logout {refreshToken}` ends the session and is always 204 for a well-formed request
(idempotent); `POST /api/auth/logout-all` (needs a valid access token) ends every session of the user and raises the token
version. The web app calls logout with `keepalive` before it clears its state.

**Two-step verification for administrators (ADR-048).** Optional TOTP (RFC 6238: HMAC-SHA-1, 6 digits, 30-second steps),
pure Java on `javax.crypto.Mac` (`domain/security/TotpGenerator.java`, `Base32.java`, `RecoveryCodes.java`), checked against
the RFC test vectors:
- Only administrators may enrol (`User.canEnrollMfa()`; the URL rules also answer 403 to everyone else). The Security
  dialog shows the secret and the `otpauth://` link as text (no QR image) once; then a first code confirms it and 8
  single-use **recovery codes** are shown once (stored as BCrypt hashes).
- The secret is stored **encrypted**: AES-256-GCM with a fresh nonce per encryption and the user id as associated data
  (`infrastructure/security/AesGcmSecretCipher.java`); the key (`iitsm.security.mfa.encryption-key`) must be set in prod or
  the application does not start.
- Sign-in becomes two steps: the password step returns `mfaRequired` and a challenge token (`purpose=mfa`, 5 minutes) and
  **no access token and no refresh token**; `POST /api/auth/mfa/verify` with the code then starts the session. The
  challenge is not accepted as a Bearer token, and an access token is not accepted as a challenge.
- A code is accepted **once**: the newest accepted time step is stored and a step that is not newer is refused (replay).
  The window is +/-1 step for phone clock drift; the comparison is constant-time.
- **Wrong codes count toward the same lockout** as wrong passwords, and the password step does not clear the failure
  count of such an account (otherwise a thief with the password could reset it before every guess of the code).
- Turning it off needs the password **and** a valid code; another administrator can reset it (the target's sessions end);
  resetting oneself is refused (`MfaService`, `AdminController.resetMfa`).

**Account lockout (ADR-033).** Five failed passwords (`iitsm.security.max-failed-logins`) lock the account for 15 minutes
(`iitsm.security.lockout-minutes`):
- `application/service/LoginAttemptService.java` counts the failure in its own short transaction, so the counter is
  saved before the 401 is returned;
- `User.recordFailedLogin(max, now, lockDuration)` sets the expiry; a failure during an active lock does not extend it; an
  expired lock is forgotten and the count restarts;
- `User.isCurrentlyLocked(now)` is the one rule used by the login, by the JWT filter (through
  `infrastructure/security/IitsmUserDetails.java`), by token refresh and by the technician roster;
- a correct password (or, with two-step verification, a correct code) resets the counter; an administrator can lift a lock
  early with `POST /api/admin/users/{userId}/unlock`.
The lockout deliberately keeps the shared (simulation) clock, so the simulation can still demonstrate a lock that expires.

**Per-address rate limiting (ADR-046).** `infrastructure/security/RateLimitFilter.java` limits `POST /api/auth/login`,
`/register`, `/refresh` and `/mfa/verify` per client address and per route with an in-memory token bucket
(`InMemoryRateLimiter`: `capacity` attempts at once, refilled continuously over the window; memory bounded). A refused
request gets HTTP 429 `ERR_RATE_LIMITED` with `Retry-After` and **never reaches the login**, so it cannot guess a password,
count as a failed login, or lock an account. The client address is the TCP peer; `X-Forwarded-For` is read only behind a
configured number of trusted proxies (`ClientIpResolver`: the Nth entry from the right), because any client can forge that
header. On by default in prod, off in dev and demo (the demo persona switcher would trip it). The filter is built by hand in
`SecurityConfig.filterChain`, so it runs only inside the security chain.

The login holds no database transaction while it checks the password (`UserService.verifyCredentials` has no
`@Transactional`), so many simultaneous logins cannot exhaust the connection pool (`LoginPoolStarvationRegressionTest`:
pool of 3, 12 simultaneous logins; `LoginTransactionContractTest` pins the annotations).

**Security events** are written to a dedicated logger, `SECURITY_AUDIT` (`application/service/SecurityAuditLog.java`):
sign-in, refresh rotation, replay detection, sign-out, sign out everywhere, enabling, disabling and resetting two-step
verification, refused codes and recovery-code use. They carry user ids and session ids only. **No password, access token,
refresh token, token hash, one-time code, recovery code, secret or challenge is ever logged**: `SecurityLogHygieneTest`
captures every application log line at DEBUG level while all of these go through the system and checks none appears.

---

## Role-based authorization

There are four roles, modelled as a class hierarchy in the domain (`User` -> `Employee`, `Technician`, `Administrator`;
`Technician` -> `TeamLead`). Authorization is checked at **three layers**, so a mistake in one does not open the system.

**1. URL rules** (`infrastructure/security/SecurityConfig.filterChain`; the first matching rule wins, so the specific rules
stand before the public `/api/auth/**` rule and `SecurityConfigOrderingTest` fails if they move):

| Path | Who |
|---|---|
| `/api/auth/logout-all` | any signed-in user |
| `/api/auth/mfa/setup`, `/enable`, `/disable` | `ADMIN` |
| `/`, static files, other `/api/auth/**` (login, register, refresh, logout, mfa/verify, me), `/api/public/**`, `/actuator/health`, Swagger paths | anyone |
| `/actuator/**` (other than health) | `ADMIN` |
| `/api/admin/technicians` | `ADMIN`, `TEAM_LEAD` |
| `/api/admin/**`, `/api/simulation/**` | `ADMIN` |
| `/api/technician/**` | `TECHNICIAN`, `TEAM_LEAD`, `ADMIN` |
| `/api/employee/**`, `/api/notifications/**`, anything else | any signed-in user |

**2. Method security.** `@EnableMethodSecurity` turns on `@PreAuthorize`, used in the controllers (for example
`hasRole('TEAM_LEAD')` on the team lead's reassignment and `hasRole('ADMIN')` on the simulation and two-step verification
endpoints).

**3. Application-layer checks (`AccessPolicy`).** URL and method rules only know the role; they cannot know whether *this*
employee owns *this* ticket. That is decided in the application services through `domain/policy/AccessPolicy.java`
(implemented by `application/policy/DefaultAccessPolicy.java`):
- `enforceCanViewIncident` and `enforceCanExecuteAction` ask the user object itself (`User.canViewIncident`,
  `User.canExecuteAction`; each subclass answers for its role by polymorphism, no `instanceof` switches) and throw
  `UnauthorizedOperationException` (HTTP 403) otherwise;
- `enforceAdmin` is called at the start of every administrator service method (for example in `AdminIncidentService`).

Examples of rules decided here: an employee can only read their own tickets; a technician sees their own tickets and the
unassigned tickets of their team, never a colleague's ticket or notes (ADR-032); a team lead sees the tickets of the
categories their team handles and may move them only between members of their own team (ADR-045).

**Persona views (data minimisation).** Each role receives its own response type, so hiding a field is enforced by the type,
not by the browser:
- `web/dto/EmployeeIncidentView.java` has no priority, SLA internals, internal notes or technician candidates; its status is
  the 5-step public status, and its activity history is an allow-list (`IncidentDtoMapper.EMPLOYEE_VISIBLE_AUDIT_ACTIONS`,
  ADR-014, ADR-037);
- `TechnicianIncidentView` adds queue priority, SLA due times, notes and the allowed actions;
- `AdminIncidentView` / `AdminIncidentDetailView` add the priority factor breakdown, the audit trail and the assignment
  explanation (administrators only, ADR-044).

**Actors are ids.** Who did something is taken from the verified token, never from the request body:
`web/security/CurrentUser.java` (`idOf`) is the one place that turns the authenticated principal into a user id, and an
ArchUnit rule forbids controllers from reading `Authentication.getName()` directly (ADR-037).

---

## Password protection

- **Hashing.** Passwords are stored only as **BCrypt** hashes (`SecurityConfig.passwordEncoder`, `BCryptPasswordEncoder`,
  default cost 10, random salt per hash). Registration encodes before saving (`UserService.registerEmployee`). The plain
  password is never stored or logged. Recovery codes are hashed the same way.
- **Comparison.** `passwordEncoder.matches` (`UserService.verifyCredentials`) compares in BCrypt's own way; the hash is never
  returned by any endpoint (`web/dto/AuthResponse` carries tokens, id, name, e-mail, role and two-step flags only).
- **Password rules.** Self-registration requires a password of at least 8 characters (`web/dto/RegisterRequest.java`).
  There is no rule on character classes and no check against a list of breached passwords (see "Known gaps").
- **Guessing.** Online guessing is slowed by three controls: the per-address rate limit, the per-account lockout (5
  failures, 15 minutes) and BCrypt's deliberate cost; an administrator who turned on two-step verification is also
  protected against a password that leaked.
- **Who may register (ADR-033).** `application/policy/RegistrationPolicy.java` refuses e-mail domains outside
  `iitsm.security.allowed-email-domains` (prod and demo: `nimbusfinserve.com`; dev and test: any) and departments the ticket
  numbering does not know. A client-supplied designation is ignored (`AuthController.register`), so nobody can register as a
  senior employee. Self-registration only ever creates an `Employee`.

---

## Input validation

Validation happens at the edge and again in the domain:
- **Request DTOs** use Jakarta Bean Validation (`@NotBlank`, `@Email`, `@Size`); controllers take them with `@Valid`. A failure
  is HTTP 400 `ERR_VALIDATION_FAILED` with one entry per field (`GlobalExceptionHandler.handleValidationException`). The
  authentication DTOs bound what the server will process: a refresh token is at most 200 characters, a one-time code at most
  32, a challenge token at most 2000.
- **Domain invariants** are checked in constructors and behaviour methods (value objects such as `Email`, `Priority`,
  `TicketNumber`, `RefreshToken`; state transitions in the `IncidentState` classes). They throw `ValidationException` (400) or
  `InvalidStateTransitionException` (409).
- **Enum text** from requests is converted by `domain/model/EnumText.java`, which answers an unknown value with a 400 that
  lists the valid values, instead of an `IllegalArgumentException` that would become a 500 (ADR-027).
- **The error contract (ADR-027).** Every client mistake is a 4xx with a stable code and the JSON shape
  `{timestamp, status, error, code, message, fieldErrors}`: 400 `ERR_VALIDATION_FAILED`, `ERR_INVALID_PARAMETER`,
  `ERR_MISSING_PARAMETER`, `ERR_MALFORMED_JSON`; 401 `ERR_INVALID_CREDENTIALS`, `ERR_INVALID_REFRESH_TOKEN`,
  `ERR_INVALID_MFA_CODE`; 404 `ERR_NOT_FOUND`, `ERR_SERVICE_NOT_FOUND`; 405 `ERR_METHOD_NOT_ALLOWED`; 409 for state conflicts
  (`ERR_MFA_ALREADY_ENABLED`, `ERR_MFA_NOT_ENROLLED` among them); 415 `ERR_UNSUPPORTED_MEDIA_TYPE`; 429 `ERR_RATE_LIMITED`.
  There is deliberately no global `IllegalArgumentException` handler: an unexpected exception is a logged 500 with a generic
  message (`GlobalExceptionHandler.handleGenericException`), so internal details (stack traces, SQL) never reach the client.
- **SQL injection.** All database access goes through Spring Data JPA, JPQL with bound parameters and JPA Criteria
  Specifications (`infrastructure/persistence/specification/`); no SQL is built by string concatenation from input.
- **Cross-site scripting (XSS).** Text is stored verbatim (ADR-015) and escaped on output: every dynamic value the web app
  writes into HTML goes through `escapeHtml` (`src/main/resources/static/app.js`). A strict Content Security Policy (next
  section) blocks inline and foreign scripts even if an escape were missed.

---

## Secure API access

- **Bearer tokens only, so CSRF protection is off on purpose.** CSRF is disabled (`SecurityConfig.filterChain`). This is safe
  here because the API authenticates *only* through the `Authorization` header, which a browser never attaches automatically
  to a cross-site request; the application sets **no cookie of any kind**, the refresh token included. CSRF protection exists
  to stop a browser from sending cookies the user did not mean to send, and there are none. (Moving the refresh token into an
  HttpOnly cookie would hide it from scripts but would need CSRF protection and would share one session between all tabs of the
  browser; ADR-047 records why it was not done.)
- **Security headers** (`SecurityConfig.filterChain`):
  - Content Security Policy `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;
    connect-src 'self'; frame-ancestors 'none'`: scripts only from the application itself, no inline scripts or event
    handlers (the front end uses `addEventListener` only; the markup tests fail on an inline `on...=` handler or an external
    URL), no framing;
  - `X-Content-Type-Options: nosniff`; `X-Frame-Options: DENY` (clickjacking); `Referrer-Policy:
    strict-origin-when-cross-origin`.
- **Short-lived credentials, revocable at once.** Access tokens live 15 minutes and die with their session; refresh tokens
  rotate and are stored hashed (see "Authentication").
- **Rate limiting** of the sign-in, registration, refresh and code-verification endpoints per client address (ADR-046).
- **No third-party code in the browser.** The front end is vanilla HTML, CSS and JavaScript served by the application; there
  are no CDN resources (project rule, checked by the markup tests).
- **Exposure in production (ADR-033, ADR-038).** In the prod profile the Swagger UI and the OpenAPI document are off
  (`application.yml`, pinned by `ExposureConfigContractTest`). Actuator exposes `health`, `info` and `metrics`; everything
  except `/actuator/health` requires the `ADMIN` role. The metric `iitsm.security.rate_limited` counts refused requests.
- **Notifications belong to their recipient (ADR-032).** Marking a notification as read checks the recipient
  (`NotificationService.markDelivered`); someone else's notification and an unknown id both answer 404, so ids cannot be probed.
- **Transport.** The application speaks plain HTTP on port 8085; TLS is expected to be terminated by a reverse proxy in front
  of it (see "Known gaps").

---

## Protection against unauthorized operations

An operation is allowed only when **all** of these agree:
1. **Authenticated** - a valid, unexpired, correctly signed access token of a live session, whose user is active, not locked
   and of the same token version (`JwtAuthenticationFilter`); for an administrator with two-step verification the session
   exists only after a valid code.
2. **Right role** - URL rule and `@PreAuthorize` (section "Role-based authorization").
3. **Right object** - `AccessPolicy` in the application service: ownership, team membership and the action
   (`IncidentAction`) checked on the actual ticket. A failure is `UnauthorizedOperationException`, HTTP 403.
4. **Right state** - the ticket's lifecycle state allows the action (State pattern, `domain/state/*`): for example a ticket
   cannot be resolved before it is in progress, a closed ticket's priority cannot be overridden (ADR-028), a ticket in triage
   cannot be moved by a team lead (ADR-045). An illegal action is `InvalidStateTransitionException`, HTTP 409.
5. **No lost update** - optimistic locking with `@Version` on incidents (`IncidentJpaEntity`) and on users, including
   technicians and their capacity (`UserJpaEntity`). Two people changing the same record at once: one succeeds, the other gets
   HTTP 409 `ERR_CONCURRENT_MODIFICATION` ("changed by someone else, reload") instead of silently overwriting
   (`GlobalExceptionHandler.handleConcurrencyFailure`, ADR-017).

Further protections against misuse of legitimate access:
- **No privilege escalation at registration** - the role is always `Employee` and the designation is fixed.
- **Sensitive account actions need more than a token**: turning off two-step verification needs the password and a code;
  "sign out everywhere" and a reset by another administrator end sessions server-side.
- **Audit trail** - every domain event (assignment, override, resolution, escalation, ...) is recorded with the acting user's id
  by `AuditTrailObserver` in the same transaction; administrators read it in the ticket dialog. Security events have their own
  log (see "Authentication").
- **Notification sending is decoupled** - notifications are sent only after the transaction commits (ADR-017), so a refused or
  rolled-back operation never tells anyone it happened.

---

## Threat table

| Asset | Threat | Control in place | Residual risk |
|---|---|---|---|
| User passwords | Theft of the database | BCrypt hashes with per-hash salt | Offline cracking of weak passwords (8-character minimum only) |
| User accounts | Online password guessing from one address | Per-address rate limit (429), lockout after 5 failures for 15 minutes, BCrypt cost | Guessing spread over many addresses is only slowed by the lockout; the limiter is per instance and off in dev and demo |
| User accounts | Attacker locks a victim out on purpose | The attacker's address is limited first; lock expires after 15 minutes; admin unlock | An attacker with many addresses can still lock a known user, 15 minutes at a time |
| User accounts | E-mail enumeration via login | Same 401 "Invalid credentials" for every failure (also for the second step) | Registration reveals an already registered e-mail |
| Administrator accounts | A leaked administrator password | Optional two-step verification (TOTP): the password alone returns a challenge, not a session; wrong codes count toward the lockout; replay refused | Optional (not forced), administrators only; a thief who also holds the phone or a recovery code is not stopped |
| Authenticator secret | Theft of the database | AES-256-GCM, nonce per encryption, user id as associated data; recovery codes BCrypt-hashed | dev and demo carry a committed development key; prod must set `MFA_ENCRYPTION_KEY` |
| Access token | Theft by script injection (XSS) | `escapeHtml` on output, strict CSP without inline scripts; 15-minute lifetime | Token sits in `sessionStorage`, readable by any script that does run, for up to 15 minutes |
| Access token | Use after sign-out or theft | 15 minutes; revoked at once by sign-out, replay detection, sign out everywhere, reset of two-step verification; lock and deactivation checked per request | A copied token works until the first of these |
| Refresh token | Theft or replay | Rotation on every use, reuse revokes the whole session, stored only as SHA-256, 8-hour absolute limit, 10 sessions per user | Readable from `sessionStorage` like the access token; the thief who uses it first wins until the owner's next refresh signs the session out |
| Access token | Forgery | HMAC signature, secret >= 32 bytes, no prod fallback secret | dev and demo profiles carry committed fallback secrets |
| Session times | Simulation clock moved by years | Security times use the real wall clock (rule R1) | - |
| Tickets and notes | Employee reads another employee's ticket | `AccessPolicy` ownership check, 403 | - |
| Tickets and notes | Technician reads a colleague's ticket | Team-queue filter by `AccessPolicy` (ADR-032) | - |
| Internal data (priority, SLA, candidates) | Over-exposure to employees and technicians | Persona response types | - |
| Notifications | Marking someone else's notification | Recipient check, 404 for foreign and unknown ids | - |
| Ticket records | Lost update by concurrent writers | `@Version` optimistic locking, 409 | Client must reload and retry by hand |
| Operations | Illegal lifecycle action | State pattern, 409 | - |
| Web page | Clickjacking, script injection, MIME sniffing | `frame-ancestors 'none'`, `X-Frame-Options: DENY`, CSP, `nosniff` | `style-src 'unsafe-inline'` is allowed |
| API documentation, metrics | Reconnaissance | Swagger off in prod; Actuator (except health) admin-only | Swagger is on in dev and demo |
| Traffic | Eavesdropping | TLS at the reverse proxy (deployment) | The application itself does not enforce HTTPS |
| Server | Request flooding of the auth endpoints | Per-address token buckets on login, register, refresh and verify; intake gate on ticket creation (ADR-018) | State is per application instance and lost on restart |
| Logs | Secrets written to logs | Dedicated `SECURITY_AUDIT` logger with ids only; `SecurityLogHygieneTest` | The login e-mail appears in WARN logs of failed logins |

---

## Configuration reference

Every security-related key, its default per profile and its environment variable. Values come from
`src/main/resources/application.yml`; the test profile has its own `src/test/resources/application.yml` (rate limiting off,
a test-only MFA key, 15-minute tokens).

| Key | Base / dev | prod | demo | Environment variable |
|---|---|---|---|---|
| `iitsm.security.jwt.secret` | committed fallback (dev only) | **none** (start-up fails without it) | committed fallback | `JWT_SECRET` |
| `iitsm.security.jwt.expiration-ms` | 900000 (15 min) | 900000 | 900000 | `JWT_EXPIRATION_MS` |
| `iitsm.security.max-failed-logins` | 5 | 5 | 5 | - |
| `iitsm.security.lockout-minutes` | 15 | 15 | 15 | `LOCKOUT_MINUTES` |
| `iitsm.security.allowed-email-domains` | empty (any domain) | `nimbusfinserve.com` | `nimbusfinserve.com` | `ALLOWED_EMAIL_DOMAINS` |
| `iitsm.security.rate-limit.enabled` | false | **true** | false | `RATE_LIMIT_ENABLED` |
| `iitsm.security.rate-limit.trusted-proxy-hops` | 0 (ignore `X-Forwarded-For`) | 0 | 0 | `RATE_LIMIT_TRUSTED_PROXY_HOPS` |
| `iitsm.security.rate-limit.max-tracked-clients` | 10000 | 10000 | 10000 | - |
| `iitsm.security.rate-limit.login-capacity` / `-window-seconds` | 10 / 60 | same | same | - |
| `iitsm.security.rate-limit.register-capacity` / `-window-seconds` | 5 / 600 | same | same | - |
| `iitsm.security.rate-limit.refresh-capacity` / `-window-seconds` | 60 / 60 | same | same | - |
| `iitsm.security.rate-limit.mfa-capacity` / `-window-seconds` | 10 / 60 | same | same | - |
| `iitsm.security.refresh.ttl-hours` | 8 | 8 | 8 | `REFRESH_TTL_HOURS` |
| `iitsm.security.refresh.max-sessions-per-user` | 10 | 10 | 10 | `MAX_SESSIONS_PER_USER` |
| `iitsm.security.refresh.cleanup-interval-ms` | 3600000 | same | same | `REFRESH_CLEANUP_INTERVAL_MS` |
| `iitsm.security.refresh.purge-after-days` | 7 | 7 | 7 | - |
| `iitsm.security.mfa.encryption-key` | committed development key | **none** (start-up fails without it) | committed development key | `MFA_ENCRYPTION_KEY` |
| `iitsm.security.mfa.issuer` | Nimbus FinServe I-ITSM | same | same | - |
| `iitsm.security.mfa.challenge-minutes` | 5 | 5 | 5 | - |
| `iitsm.security.mfa.window-steps` | 1 | 1 | 1 | - |
| `iitsm.security.mfa.recovery-code-count` | 8 | 8 | 8 | - |
| `springdoc.api-docs.enabled`, `springdoc.swagger-ui.enabled` | true (library default) | false | true | - |
| `management.endpoints.web.exposure.include` | health, info, metrics | same | same | - |
| `iitsm.demo-mode` | true | false | true | `DEMO_MODE` (base only) |

A non-positive rate limit, refresh or MFA setting, and a missing or malformed MFA key, stop the start-up with a message that
names the property. `iitsm.demo-mode` shows the one-click demo accounts on the sign-in page (`/api/public/config`); it is off
in prod, and the prod profile does not seed demo users (`iitsm.simulation.auto-seed: false`).

Database migrations for this work: `V12__refresh_tokens.sql` (refresh tokens, hashed) and `V13__mfa.sql` (two-step
verification columns and recovery codes).

---

## Implemented hardening

The authentication hardening was done in phases after the first version of this document (which listed these as planned).

| Phase | What | Decision | Evidence |
|---|---|---|---|
| S2 | Per-address rate limiting of login, register, refresh and code verification; 429 with `Retry-After`; proxy-aware client address | ADR-046 | `InMemoryRateLimiterTest`, `RateLimitFilterTest`, `RateLimitRegressionTest`; `rate_limit_check.py` |
| S3 | 15-minute access tokens in every profile; rotating refresh tokens stored hashed; replay detection; sign-out and sign out everywhere that revoke at once; real wall clock for security times | ADR-047 | `RefreshTokenServiceTest`, `RefreshTokenRegressionTest`, `SimulationClockDoesNotExpireSessionsRegressionTest`; `refresh_tokens_check.py`, `refresh_browser.py` |
| S4 | Optional TOTP two-step verification for administrators: encrypted secret, no code replay, recovery codes, lockout on wrong codes, reset by another administrator | ADR-048 | `TotpGeneratorTest` (RFC 6238 vectors), `MfaServiceTest`, `MfaLoginRegressionTest`; `mfa_check.py`, `mfa_browser.py` |

Each phase was also checked by breaking its key rules on purpose (mutation checks) and watching a test fail; the results are in
`docs/auth-hardening-summary.md`.

---

## Known gaps

Stated honestly; these are what remains after S2 to S4.

1. **Tokens in `sessionStorage`** (`static/app.js`, `storeSession`). Any script running on the page can read the access and the
   refresh token. Mitigated by the strict CSP (no inline or foreign scripts), by escaping every dynamic value and by the short
   lifetimes and revocation above, not removed. One session per browser tab is a deliberate choice (different users can work
   in different tabs during a demonstration, which a shared cookie would not allow).
2. **Rate limiting is per application instance.** The state is in memory: several instances multiply the allowance and a
   restart forgets it; users behind one shared address with no trusted proxy configured share a bucket; an attacker with many
   addresses is not limited by it. It is off in dev and demo unless `RATE_LIMIT_ENABLED` is set.
3. **Committed fallback secrets in dev and demo** (`application.yml`: the JWT secret and the MFA encryption key). Anybody can
   sign tokens for an instance started with those profiles and no `JWT_SECRET`, and decrypt authenticator secrets of such a
   database. The prod profile has neither fallback and refuses to start without them. Separately, the prod-only guard in
   `JwtTokenProvider` compares against a constant that is not the value in `application.yml`; prod is still safe because its
   fallback is empty, but the guard itself checks the wrong value.
4. **TLS is assumed to be terminated by a proxy.** The application does not redirect to HTTPS or send
   `Strict-Transport-Security`.
5. **Two-step verification is optional and for administrators only.** Nothing forces an administrator to enrol, and employees,
   technicians and team leads cannot. There is no QR image (the secret and the link are shown as text). A code is accepted once
   per account, so two sign-ins inside one 30-second step need the next step's code or a recovery code. Two simultaneous
   verifications with one code can end as one success and one 409 (not pinned by a test). The challenge token is not single-use
   (it expires after 5 minutes).
6. **The 8-hour session limit is absolute.** A longer working day signs the user out and needs a new sign-in. A lost refresh
   response also signs the user out (no grace window, by design). Sessions are not listed or revoked one by one from a screen;
   "sign out everywhere" is the only bulk control.
7. **One extra indexed query per authenticated request** checks that the session is alive. The recovery-code check does up to 8
   BCrypt comparisons while holding one database connection (under the `mfa` rate limit).
8. **Two error-body shapes.** The 401 and 403 bodies written by the security layer (`SecurityConfig` entry point and access
   denied handler, `JwtAuthenticationFilter`) are `{errorCode, message}`, while every other error, the 429 of the rate limiter
   included, is `{timestamp, status, error, code, message, fieldErrors}`. Clients must read both `code` and `errorCode` (the
   web app's Security dialog does exactly that).
9. **Registration reveals existing e-mail addresses**: it answers "Email address is already registered"
   (`UserService.registerEmployee`), unlike the login.
10. **Weak password rule**: 8 characters minimum, no complexity or breached-password check.
11. **The demo password is in the shipped front end** (`static/app.js`, `demoPassword`, used by the demo persona switcher). It
    only works where demo accounts were seeded (dev and demo, not prod), but it is visible to anyone who loads the page in any
    profile.
12. **The login e-mail appears in WARN logs** of failed logins (`UserService`, `LoginAttemptService`). No password or token is
    logged, but the address is personal data.
13. **Production database fallback credentials** (`application.yml`, prod block: user and password `postgres`) apply if
    `DB_USER`/`DB_PASSWORD` are not set.
14. **No password change or reset endpoint.** A user cannot change their password in the application, so the token version is
    raised only by "sign out everywhere" (an administrator's unlock lifts the lock but does not end tokens).
