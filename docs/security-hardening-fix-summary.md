# Security hardening: team queue, notifications, login, lockout, exposure (Phase E)

Remaining-defects plan, Phase E. ADR-032 (stress-audit items M2, M3; decision D10) and ADR-033 (P1 login half, M4, L4;
decisions D11, D12). No migration: `locked_until` already existed.

## What was wrong

1. **M2, the team queue showed colleagues' tickets.** `IncidentLifecycleService.getTeamQueue` loaded every ticket and kept
   those of the team's services without asking the access policy, so a technician saw tickets, and internal notes, that
   colleagues were working on.
2. **M3, any user could mark any notification as read** by knowing its id.
3. **P1, the login could starve the connection pool.** `UserService.authenticate` held a connection through the BCrypt
   check while `LoginAttemptService` asked for a second one in a `REQUIRES_NEW` transaction. Twelve simultaneous logins on
   a pool of 3 ended in HTTP 500 after the 3 s connection timeout.
4. **M4, a lock never ended.** Five wrong passwords set `locked`, `locked_until` was never written and `isCurrentlyLocked`
   was never called: anyone could lock any account, administrators included, for good.
5. **L4, exposure.** Registration accepted any e-mail domain and any department text (a typo got a ticket counter of its
   own); Swagger was open in production; a login alias `tech.database@` existed in two classes.

## What changed

| Area | Change |
|---|---|
| Team queue | `getTeamQueue`: administrator everything; team lead the tickets of the team's services and their own; technician their own and the unassigned tickets of the team. Two queries (`IncidentRepository.findByServiceIdIn`, `findByAssigneeId`) instead of `findAll`; every ticket passes the new `AccessPolicy.canViewIncident`. Web: the technician's button reads "Unassigned Team Tickets" |
| Notifications | `NotificationService.markDelivered(id, actorId)`: only the recipient; anyone else, and an unknown id, get 404 `ERR_SERVICE_NOT_FOUND` |
| Login | `UserService` has no class-level `@Transactional` and `authenticate` has none, not even `SUPPORTS` (measured: `SUPPORTS` still held the connection); the write methods declare their own. `LoginAttemptService` uses ordinary transactions (no `REQUIRES_NEW`) |
| Lockout | `User.recordFailedLogin(max, now, lockDuration)` sets `lockedUntil`; an expired lock is forgotten (the count restarts); a failure inside an active lock does not extend it; `resetFailedLogins` clears the expiry. `isCurrentlyLocked(now)` decides in `authenticate`, `IitsmUserDetails` (JWT filter, `CustomUserDetailsService` takes the `Clock`) and the technician roster. `iitsm.security.lockout-minutes` (15). Same generic message; administrators follow the same rule |
| Registration | New `RegistrationPolicy`: `iitsm.security.allowed-email-domains` (empty = any, prod and demo default to `nimbusfinserve.com`, `ALLOWED_EMAIL_DOMAINS`), exact domain match; department must be registered (400 lists the valid ones). The registry gains `isRegistered`, `registeredDepartments`, and "Human Resources" (HUM) and "Risk & Compliance" (RIS) with the codes the fallback already gave them |
| Swagger, alias | `springdoc.*.enabled: false` in the prod profile; the `tech.database@` alias is removed |

No schema or migration change. Asset version stays `?v=3.6` (only `index.html` changed: the button label).

## Behaviour changed on purpose

- A technician's team view no longer lists colleagues' assigned tickets; a team lead's does.
- Marking someone else's, or an unknown, notification as read is a 404 (before: 204).
- A locked account unlocks itself after 15 minutes; a lock without an expiry (from an older version) stays until an administrator lifts it.
- Registration outside the configured domains, or with an unknown department, is a 400 (any profile for the department).
- `Round3RegressionTest.testVpnIncidentCreationAndTechnicianQueue` asserts the ticket for the assignee and the team lead instead of for `tech.network@`.

## Tests (suite 989 to 1043)

| Class | Tests | What it proves |
|---|---|---|
| `LoginPoolStarvationRegressionTest` | 1 | 12 simultaneous logins (half wrong) on a 3-connection pool all answered; failed on the original code |
| `LoginTransactionContractTest` | 3 | the annotations that cause it (no class-level or method transaction on `authenticate`, no `REQUIRES_NEW`) |
| `UserLockoutExpiryTest` | 8 | expiry, restart of the count, no extension, reset, legacy lock, administrators, invalid duration |
| `AccountLockoutRegressionTest` | 7 | login and JWT filter honour the expiry (14 min locked, 16 min free), no re-lock after one wrong password, same message, roster |
| `NotificationOwnershipRegressionTest` | 5 | other user, staff, owner, unknown id, fields kept |
| `TeamQueueAccessRegressionTest` | 6 | colleague, team lead, administrator, other team, shared unassigned ticket, employee |
| `RegistrationPolicyTest` | 6 | domains (look-alikes), several domains, departments, form departments |
| `ExposureHardeningRegressionTest` | 6 | external domain, company domain, no account created, Swagger closed, alias gone (login and JWT lookup) |
| `RegistrationDepartmentRegressionTest` | 4 | unknown department and typo are 400, default, every form option accepted |
| `ExposureConfigContractTest` | 5 | what each profile of the shipped `application.yml` says |
| `TechnicianTeamModeMarkupTest` | 3 | button label, endpoints, registration message |

Mutation checks (each made tests fail, then undone): class-level transaction and `REQUIRES_NEW` back (the burst fails only with both, the contract test fails with either), `isLocked()` in the JWT details, in the login and in the roster, no expiry set, no owner check, unfiltered team queue, no registration policy, any department accepted, alias back in `CustomUserDetailsService` (found a gap: only a direct test of the details service sees it, now added).

## Verification

- Full `mvn -o clean verify`: 1043 tests, 0 failures, 0 skipped, JaCoCo gate met (89.9 % lines).
- Every class in its own JVM (138 classes): 1043 tests, 0 failures.
- Live, against the running app (company-domain registration, Swagger off, default pool of 10; `scripts/live-checks/security_checks.py`, 22 of 22): 30 simultaneous logins (15 wrong) answered 200 or 401 in under 0.3 s; lock held at 14 minutes and gone at 16 (simulated clock), old token works again; notification ownership 404, 404, 204; colleague does not see the ticket, assignee, lead and administrator do; external domain and unknown department 400; `/v3/api-docs` and `/swagger-ui/index.html` 404; alias 401. No ERROR in the app log.
- Real browser (Playwright and Edge, `scripts/live-checks/team_view_browser.py`, 7 of 7): the button reads "Unassigned Team Tickets", a colleague's team view lacks the ticket, the assignee's own queue has it, the Team Lead tab loads, the registration form shows the server's message; no console error. Two checks of my own scripts were wrong and fixed (an empty set treated as "failed"; the Team Lead tab shows only unassigned and at-risk tickets).
- Not run: CI on JDK 21; the prod and demo profiles against a real PostgreSQL (their yml values are pinned by `ExposureConfigContractTest` only).

## Known limits

- The lock counts per account, not per address: someone can still lock an account for 15 minutes at a time.
- `LoginAttemptService` swallows its own persistence errors so the login answer is not lost (broad catch at a boundary).
- `TicketSequenceRowCreator` still opens a nested transaction (ticket-counter half of P1, deferred by D22).
- A locked account whose lock expired shows `locked = true` in the database until its next successful login; readers use `isCurrentlyLocked(now)`.
- The Team Lead tab lists only unassigned and at-risk tickets; the whole-team view is the API.
- A VPN ticket was seen routed to the Cloud team lead in the live run: the routing weakness for Phase G.
