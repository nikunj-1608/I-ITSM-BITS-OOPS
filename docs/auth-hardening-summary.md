# Authentication hardening: summary

Phases S1 to S5 of the authentication hardening work (started 2026-11-19, deadline 2026-11-22). Each phase section is
appended when the phase is done. Nothing in this work is committed (instruction of 2026-11-19); the changes sit in
the working tree next to the uncommitted Team Lead console work (ADR-045).

Rules for every phase: security times use the real wall clock (`wallClock` bean, never the simulation clock);
no token, secret or code is ever logged or returned (`SecurityLogHygieneTest`); the domain has no Spring imports;
failure messages are generic.

## Overview

| Phase | What | ADR | New tests | Suite after | One-JVM-per-class | Live checks |
|---|---|---|---|---|---|---|
| S1 | Security considerations document (no code) | none | 3 | 1671 | 237 classes | none (no code) |
| S2 | Per-address rate limiting | ADR-046 | 45 | 1716 | 244 classes | `rate_limit_check.py` 11 of 11 |
| S3 | Refresh tokens with revocation, 15-minute access tokens | ADR-047 | 94 | 1810 | 257 classes | `refresh_tokens_check.py` 26 of 26, `refresh_browser.py` 10 of 10 |
| S4 | Optional TOTP two-step verification for administrators | ADR-048 | 134 | 1944 | 272 classes (one of my tests failed alone, fixed in S5) | `mfa_check.py` 27 of 27, `mfa_browser.py` 21 of 21 |
| S5 | Final documentation and reconciliation | cross-references | 0 | 1944 | 272 classes | all of the above re-run on the final code |

Migrations: `V12__refresh_tokens.sql`, `V13__mfa.sql` (V7 and V8 stay reserved). New configuration: `iitsm.security.rate-limit.*`,
`iitsm.security.refresh.*`, `iitsm.security.mfa.*`; every key with its default per profile and its environment variable is in
`docs/security-considerations.md`, "Configuration reference". New error codes: `ERR_RATE_LIMITED` (429), `ERR_INVALID_REFRESH_TOKEN` (401),
`ERR_INVALID_MFA_CODE` (401), `ERR_MFA_ALREADY_ENABLED` (409), `ERR_MFA_NOT_ENROLLED` (409).

### Where each pattern lives

| Pattern or rule | Where | Why |
|---|---|---|
| Strategy (port and adapter) | `RateLimiter` (domain) and `InMemoryRateLimiter`; `OpaqueTokenPort` and `SecureOpaqueTokenGenerator`; `SecretCipherPort` and `AesGcmSecretCipher` | the algorithm (bucket, hash, cipher) can be replaced without touching callers; a shared store could replace the in-memory limiter |
| Intercepting Filter | `RateLimitFilter`, `JwtAuthenticationFilter` | cross-cutting checks before the controller; the rate limiter is built by hand so it runs only in the security chain |
| Policy (pure decision) | `RefreshTokenRotation`, `TotpGenerator.verify`, `RegistrationPolicy` | every outcome is a unit test; no clock or repository inside |
| Repository (port and adapter) | `RefreshTokenRepository`, `MfaRecoveryCodeRepository` and their adapters | conditional single-statement updates give "once" semantics (a token rotates once, a recovery code is used once) |
| Value Object | `RefreshToken`, `MfaRecoveryCode`, `RateLimitDecision` | immutable, invariants in the constructor |
| Sealed result type | `LoginResult` (`Authenticated` or `MfaRequired`) | the caller must handle both outcomes of the password step |
| Application Service | `RefreshTokenService`, `AuthSessionService`, `MfaService` | orchestrate transactions and the domain's decisions |
| Facade over a logger | `SecurityAuditLog` | the one place that writes security events, which only receives ids |
| R1, real time for security | `SecurityTimeConfig` (`wallClock`), injected by name | the simulation clock must not expire or extend a session |
| Tell, don't ask | `User.canEnrollMfa()`, `beginMfaEnrollment`, `recordMfaStep` | the aggregate decides; services do not test the role |

### Overall known gaps

The complete list, with the reasons, is "Known gaps" in `docs/security-considerations.md` and items 37 to 40 of `CLAUDE.md` section 7. In short:
tokens in `sessionStorage`; rate limiting per application instance; committed development secrets in dev and demo (JWT secret and MFA key); TLS
terminated at a proxy; two-step verification optional and for administrators only, with no QR image; an absolute 8-hour session; no password
change endpoint; two error-body shapes in the security layer; and the smaller items (registration reveals e-mail addresses, an 8-character password
rule, the demo password in the shipped `app.js`, the login e-mail in WARN logs, fallback database credentials in prod).

### Not run

CI on JDK 21 (the gates ran on the development machine's JDK 24 with JaCoCo 0.8.13); browsers other than Microsoft Edge; the prod and demo
profiles on a real PostgreSQL (the migrations V12 and V13 were run on a real PostgreSQL 14 in the test JVM, the application itself was not started
in those profiles).

## Phase S1: security-considerations document (no code)

- **Built**: `docs/security-considerations.md` for the course evaluators: the six sections of the project
  specification (Authentication, Role-based authorization, Password protection, Input validation, Secure API access,
  Protection against unauthorized operations), a threat table, the configuration reference, 14 known gaps and the
  planned hardening. Every claim was read in the code and cited by file and line.
- **Test**: `SecurityConsiderationsDocTest` (3, no Spring): the file exists and has the six headings and the four
  supporting sections. A mutation (a renamed heading) made it fail.
- **Found while checking the facts** (all written into the document as known gaps): the token-version revocation
  works but nothing calls it (`User.unlock()` is not used by production code); the prod-only guard in
  `JwtTokenProvider` compares against a constant that is not the value in `application.yml` (prod is still safe,
  its fallback secret is empty); registration reveals existing e-mail addresses; the demo password is in the shipped
  `app.js`; the login e-mail is in WARN logs; prod has fallback database credentials.
- **Verified**: `mvn -o clean verify` 1671 tests, 0 failures, coverage gate met; every class in its own JVM
  (237 classes) 1671 tests, 0 failures, 0 skipped.

## Phase S2: per-IP rate limiting (ADR-046)

- **Built**: `RateLimiter` port and `RateLimitDecision` (domain/policy), `InMemoryRateLimiter` (token bucket in a
  `ConcurrentHashMap`, bounded), `ClientIpResolver` (peer address; the N-th entry from the right of
  `X-Forwarded-For` only with `trusted-proxy-hops = N`), `RateLimitRules`, `RateLimitFilter` (built with `new` in
  `SecurityConfig.filterChain`, before `JwtAuthenticationFilter`; 429 `ERR_RATE_LIMITED`, `Retry-After`, metric
  `iitsm.security.rate_limited`), `RateLimitConfig`, `SecurityTimeConfig` (the `wallClock` bean; the simulation clock
  became `@Primary`). Config `iitsm.security.rate-limit.*` (off in dev, demo and test; on in prod; env
  `RATE_LIMIT_ENABLED`, `RATE_LIMIT_TRUSTED_PROXY_HOPS`). The sign-in and registration forms show "Too many attempts
  from this network. Try again in N seconds." (asset version 4.6; the old 429 text claimed a lock, which was wrong).
- **Patterns**: Strategy (port `RateLimiter`, adapter `InMemoryRateLimiter`), Intercepting Filter
  (`RateLimitFilter`), Value Object (`RateLimitDecision`).
- **Tests (45 new, suite 1671 -> 1716)**: `InMemoryRateLimiterTest` (11: burst then refuse, exact refill, retry-after,
  clock going back, independent keys, 50,000 keys stay under the limit, purge keeps an active client refused, 200
  threads on capacity 10 get exactly 10), `ClientIpResolverTest` (12), `RateLimitFilterTest` (8), `RateLimitRegressionTest`
  (4, HTTP: 4th login 429 and another address works, refusals never count as failed logins or lock, forged
  `X-Forwarded-For` ignored, login works after `Retry-After`), `RateLimitConfigContractTest` (5, reads the shipped yml),
  `RateLimitMarkupTest` (4), `SecurityLogHygieneTest` (1: every `com.iitsm` log line at DEBUG while passwords, a
  token and 429s go through contains no password and no token). The nine asset-version tests now pin 4.6.
- **Mutation checks** (each broke the tests, then undone): trust `X-Forwarded-For` always (11 errors), a refused
  request still reaches the controller (8 failures), no memory bound (2), filter applied to GET (1), refill ignores
  the clock (6). Note: the code was written before the tests, so the tests did not fail first; the mutation checks are
  the proof that they catch the rules.
- **Live check**: `scripts/live-checks/rate_limit_check.py` (API and Edge), 11 of 11 checks on a fresh application
  started with `--iitsm.security.rate-limit.enabled=true --iitsm.security.rate-limit.login-capacity=5`: the 6th login
  is 429 with the standard body and `Retry-After`, a forged header does not help, 10 refused wrong passwords never lock
  the account, registration has its own bucket, `GET /api/auth/me` is never limited, the metric counts the refusals,
  the browser shows the wait. `security_checks.py` (22), `routing_check.py` and `action_buttons_browser.py` still
  pass with the limit off (they need it off: 30 logins from one address).
- **Verified**: `mvn -o clean verify` 1716 tests, 0 failures, coverage gate met; every class in its own JVM (244 classes)
  1716 tests, 0 failures, 0 skipped.
- **Known limits**: state is per application instance and lost on restart; users behind one NAT address with no
  trusted proxy configured share a bucket; an attacker with many addresses is not slowed by this control; the
  security-layer 401/403 bodies still use `errorCode` (S1 gap 8).

## Phase S3: refresh tokens with revocation (ADR-047, migration V12)

- **Built**: sessions made of a 15-minute access token and a rotating refresh token. `RefreshToken` (domain value
  object), `RefreshTokenRotation` (pure decision: `ROTATE`, `REUSED`, `EXPIRED`, `REVOKED`), `RefreshTokenRepository`
  (port), `InvalidRefreshTokenException` (401 `ERR_INVALID_REFRESH_TOKEN`), `OpaqueTokenPort` and
  `SecureOpaqueTokenGenerator` (32 random bytes, stored only as SHA-256), `RefreshTokenService`, `AuthSessionService`,
  `AuthSession`, `SecurityAuditLog` (logger `SECURITY_AUDIT`), the persistence adapter with conditional single-statement
  updates, Flyway `V12__refresh_tokens.sql`, `RefreshTokenCleanupScheduler`, `RefreshRequest`/`LogoutRequest`, new
  endpoints `POST /api/auth/refresh`, `/logout`, `/logout-all`. `UserService.verifyCredentials` was extracted from
  `authenticate` (still no transaction). `JwtTokenProvider` uses the wall clock and adds the `sid` claim;
  `JwtAuthenticationFilter` rejects a token whose session is revoked. Access token 15 minutes in every profile (the
  120-minute dev and demo overrides were removed). `app.js`: `authorizedFetch` repeats a 401 once after a single-flight
  renewal, sign-out calls `/api/auth/logout` with `keepalive`, the simulation-status poll uses the same renewal (asset
  version 4.7). The SPA has no SSE client (it polls), so the "notification stream reconnect" of the brief did not apply.
- **Patterns**: Policy (`RefreshTokenRotation`), Repository (port and adapter), Adapter (`SecureOpaqueTokenGenerator`),
  Facade (`SecurityAuditLog`), Value Object (`RefreshToken`), Application Service (`RefreshTokenService`).
- **Decisions I made where the brief left room** (all in ADR-047): the 8 hours are absolute, not renewed by use
  (a rotated token inherits its predecessor's expiry); the lock check at refresh uses the shared clock, because the
  lock's expiry was set with it (R1 keeps lockout on the shared clock); `logout` accepts a missing token and answers
  204, an oversized value is 400.
- **Tests (94 new, suite 1716 -> 1810)**: `RefreshTokenTest` (4), `RefreshTokenRotationTest` (6),
  `RefreshTokenServiceTest` (14: rotate, replay revokes the family, expiry boundary, inactive, locked, version
  mismatch, session cap, idempotent logout, logout-all, one generic exception), `AuthSessionServiceTest` (2),
  `RefreshTokenRepositoryAdapterTest` (10, incl. 200 threads claiming one token: one winner),
  `RefreshTokensMigrationH2Test` and `RefreshTokensMigrationPostgresTest` (5 each, the latter on a real PostgreSQL 14), `JwtTokenProviderSessionTest`
  (6), `AuthTokenFilterTest` (4), `RefreshTokenRegressionTest` (19, HTTP), `SimulationClockDoesNotExpireSessionsRegressionTest`
  (4: a simulated day and five simulated years), `RefreshConfigContractTest` (4), `RefreshMarkupTest` (8),
  `SecurityLogHygieneTest` (+1), `LoginTransactionContractTest` (+2). Ten asset-version pins now say 4.7.
- **Mutation checks** (each broke tests, then undone): plain token stored (4 failures), rotation without revoking the
  old token (11), reuse not revoking the family (6), filter ignoring `sid` (10), logout not revoking (6), locked/inactive/
  version check skipped (8), shared simulation clock used for expiry (3), and in the browser script the single-flight
  guard removed (the check "exactly one refresh request" failed: 2 sent, the user was signed out).
  As in S2, the code was written before the tests; the mutation checks are the proof that the tests bind the rules.
- **Live checks**: `refresh_tokens_check.py` 26 of 26 (API: sign-in, rotation, replay, sign-out, sign out everywhere,
  20 simultaneous refreshes give one 200, bad input never 500, a simulated day); `refresh_browser.py` 10 of 10 in
  Microsoft Edge with `--iitsm.security.jwt.expiration-ms=5000` (still signed in after the token expired, two parallel
  calls send one refresh request, sign-out sends one logout and kills the refresh token, no console error besides the
  401 answers and the favicon 404); `security_checks.py`, `routing_check.py` and `action_buttons_browser.py` still pass.
- **Verified (S3)**: `mvn -o clean verify` 1810 tests, 0 failures, coverage gate met; every class in its own JVM
  (257 classes) 1810 tests, 0 failures, 0 skipped.
- **Known limits**: one extra indexed query per authenticated request; a lost refresh response signs the user out (no
  grace window, by design); the 8-hour absolute limit ends a longer working day; sessions are not listed or revoked
  one by one from a screen; the tokens are still readable from `sessionStorage` by a script that runs on the page.

## Phase S4: optional TOTP two-step verification for administrators (ADR-048, migration V13)

- **Built**: RFC 6238 time-based codes in pure Java (`TotpGenerator`, `Base32`, `RecoveryCodes` in `domain/security`),
  `User` state and rules (`mfaEnabled`, encrypted `mfaSecret`, `mfaLastStep`, `beginMfaEnrollment`, `enableMfa`,
  `disableMfa`, `recordMfaStep`, `canEnrollMfa`, true only for `Administrator`), `SecretCipherPort` and
  `AesGcmSecretCipher` (AES-256-GCM, random nonce, user id as associated data, fail-fast key), `MfaRecoveryCode` with
  its port, JPA entity and adapter, `MfaService` (setup, confirm, disable, admin reset, second sign-in step),
  `LoginResult` (sealed: `Authenticated` or `MfaRequired`) and the extended `AuthSessionService`, the challenge token
  (`purpose=mfa`, 5 minutes) in `TokenPort` and `JwtTokenProvider`, three exceptions (`ERR_INVALID_MFA_CODE` 401,
  `ERR_MFA_ALREADY_ENABLED` 409, `ERR_MFA_NOT_ENROLLED` 409), DTOs, endpoints `POST /api/auth/mfa/verify`, `/setup`,
  `/enable`, `/disable` and `POST /api/admin/users/{id}/mfa/reset`, `/me` returns `mfaEnabled`, Flyway `V13__mfa.sql`,
  `iitsm.security.mfa.*` (prod has no default key). Front end (asset version 4.8): second sign-in step, an
  administrators-only Security button, a Security dialog through the shared `openModal`.
- **Patterns**: Strategy-style port/adapter for the cipher, Application Service (`MfaService`), sealed result type
  (`LoginResult`), Value Object (`MfaRecoveryCode`), Policy-like pure functions in `TotpGenerator`.
- **Decisions I made where the brief left room** (all in ADR-048): the password step does **not** clear the failure
  count of an account with two-step verification (otherwise an attacker who knows the password could reset the count
  before every guess of the code; found while designing the tests); the secret is encrypted with the user id as
  associated data; a recovery code is accepted wherever a one-time code is (including to turn it off); turning off one's own
  two-step verification leaves one's sessions alone, while a reset by another administrator ends the target's sessions; a wrong code and a wrong
  password in the dialog never sign the user out.
- **Tests (134 new, suite 1810 -> 1944)**: `Base32Test` (11, RFC 4648 vectors), `TotpGeneratorTest` (16, RFC 6238
  Appendix B vectors, window, replay), `RecoveryCodesTest` (6), `UserMfaTest` (9), `AesGcmSecretCipherTest` (8, round
  trip, every byte tampered, wrong key, owner binding, unique nonces, fail-fast), `MfaServiceTest` (22),
  `AuthSessionServiceMfaTest` (5), `MfaChallengeTokenTest` (6), `MfaRecoveryCodeRepositoryAdapterTest` (5, incl. 200
  threads using one code: one winner), `MfaMigrationH2Test` and `MfaMigrationPostgresTest` (5 each), `MfaConfigContractTest`
  (4), `MfaLoginRegressionTest` (18, HTTP), `SecurityConfigOrderingTest` (4), `MfaMarkupTest` (9), `SecurityLogHygieneTest`
  (+1), `AuthSessionServiceTest` rewritten (2). Twelve asset-version pins now say 4.8.
- **Mutation checks** (each broke tests, then undone): replayed step accepted (5 failures), challenge purpose check
  skipped (2), `purpose=mfa` accepted as a Bearer token (2), secret stored unencrypted (35), non-administrator
  enrolment allowed (3), a wrong code skips the failure counter (7), MFA matchers moved after `permitAll` (2), the
  password step clears the failure count (1), sign-in ignores two-step verification (10); in the browser script the
  `mfaRequired` branch removed (the check timed out waiting for the code step) and the Security button shown to every
  role (the employee check failed). As in S2 and S3, the code was written before the tests.
- **Live checks**: `mfa_check.py` 27 of 27 (API, own TOTP with `hmac` and `struct`, the seeded IT manager as the account
  under test and always switched off again), `mfa_browser.py` 21 of 21 in Edge (button for administrators only, setup
  with the secret and link as text and no QR image, a wrong code shows an error and does not sign out, 8 recovery
  codes, the code step after the password, recovery code once, turning it off, Tab trap, no console error beside the
  401 answers). The browser run found a real defect, fixed: signing out left the Security dialog (which can show a
  secret) open over the sign-in card; sign-out now closes it. Earlier live checks re-run on the S4 code:
  `refresh_tokens_check.py`, `refresh_browser.py`, `rate_limit_check.py`, `security_checks.py`, `routing_check.py`
  and `action_buttons_browser.py` all pass; no ERROR in the app logs.
- **Known limits**: optional, administrators only, no forced enrolment; no QR image (secret and link as text); the
  committed development key in dev and demo; a code is accepted once per account, so two sign-ins in one 30-second
  step need the next step's code or a recovery code; the challenge is not single-use (each code is); two simultaneous
  verifications with one code can end as one success and one 409 (not pinned by a test); a recovery-code attempt checks
  up to 8 BCrypt hashes while holding one connection.

## Phase S5: final documentation and reconciliation

- **Changed (documentation only, plus one test)**: `docs/security-considerations.md` rewritten against the final code: the Authentication section now covers
  15-minute tokens, rotating refresh tokens, revocation, sign-out, two-step verification, rate limiting and the security audit log; "Planned hardening" became
  "Implemented hardening"; the configuration table lists every `iitsm.security.*` key with its default per profile and environment variable; the threat table and the
  known gaps were updated (the three gaps S2 to S4 closed are gone, new ones such as per-instance rate limiting, the committed MFA development key, the absolute 8-hour
  session and the lack of a password-change endpoint were added). Citations now name the file and the class or method, because line numbers moved with S2 to S4.
  `docs/auth-hardening-summary.md` got the overview, the pattern table and this section. `docs/uml/auth-hardening.puml` has the two sequence diagrams (sign-in with
  two-step verification; refresh rotation with replay detection). ADR-046 to ADR-048 cross-reference ADR-033, which got an amendment note. `CLAUDE.md`: reconciled
  line, docs map, Flyway V12 and V13, security stack, package tree, REST surface and error codes, the new section 3.16, test counts, live-check notes, asset version 4.8,
  the change log for S1 to S5 and items 37 to 40 of section 7. `SecurityConsiderationsDocTest` now requires the heading "Implemented hardening".
- **Found by the final one-JVM-per-class run of S4 and fixed**: `SecurityLogHygieneTest.mfaFlowLogsNoSecret` failed alone (it used the seeded administrator and the
  class never seeded the company data; it passed in the shared run only because other classes had seeded the shared database). It now seeds like the other regression
  classes (ADR-025). The 1944 tests and 272 classes of the earlier run were otherwise all green.
- **Verified on the final code**: `mvn -o clean verify` 1944 tests, 0 failures, 0 skipped, JaCoCo gate met (92.5 % lines); every class in its own JVM (272 classes)
  1944 tests, 0 failures, 0 skipped.
- **Live checks on the final code, all passing, no ERROR in the app logs**: `mfa_check.py` 27 of 27; `mfa_browser.py` 21 of 21 (Edge); `refresh_tokens_check.py` 26 of 26;
  `refresh_browser.py` 10 of 10 (Edge, 5-second access token); `rate_limit_check.py` 11 of 11 (rate limit on, capacity 5); `security_checks.py` (22, production-style
  start arguments); `routing_check.py`; `team_view_browser.py` (Edge); `action_buttons_browser.py` (Edge, every action button of every role).
- **Not run**: CI on JDK 21 (the gates ran on the development machine's JDK 24); browsers other than Microsoft Edge; the prod and demo profiles on a real PostgreSQL
  (V12 and V13 ran on a real PostgreSQL 14 in the test JVM).
- **Nothing is committed**, at the user's instruction. All of S1 to S5 sits in the working tree next to the uncommitted Team Lead console work (ADR-045).
