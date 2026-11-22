# "Who did it" and what employees may read (Phase H)

Remaining-defects plan, Phase H. ADR-037 (CLAUDE.md section 7 items 2 and 13; decisions D19 and D20). No schema change and
no migration. One screen changed (the admin ticket dialog); asset version 3.7.

## In plain words

Two things were wrong, and both are about what the system stores or shows about people.

1. **It stored e-mail addresses where it should store ids.** Every action ("assigned by", "priority changed by", "note
   written by", "resolved by", the audit trail) needs to say *who* did it. The system should save the user's **id** (for
   example `ADMIN-SYS-01`), but the web layer passed the **login e-mail** along, and the services saved whatever they were
   given. The same column held ids for system events and e-mails for people. A note author even showed as an e-mail
   address, because a name lookup used the wrong key.
2. **Employees could read internal facts in their ticket history.** The history said "Marked as duplicate of
   FIN-2026-000042" (another ticket, maybe someone else's) and "Technician TECH-NET-2 requested reassignment: <the
   technician's own reason>".

## What changed

| Area | Change |
|---|---|
| `web.security.CurrentUser` (new) | the one place that turns the signed-in user into their **id**; an e-mail that matches nobody is refused |
| the four controllers (technician, employee, admin, notification) | ask `CurrentUser` instead of reading `Authentication.getName()` |
| `IncidentLifecycleService`, `AdminIncidentService` | store `actor.getId()` for the audited actor, the event actor, the note author, the resolver (the `Resolution` is re-signed) and the override, whatever the caller passed; `addNote` lost its `authorName` argument (a note is signed with the author's own name) |
| `UserRepository.findAllByIds`, `ActorNames`, `IncidentActors`, `UserService.namesById` | read the names of all actors of a response in **one** query |
| API fields (new) | `priorityDecision.overriddenByName`, `resolution.resolvedByName`, `auditTrail[].actorName`; `null` when the actor is not a user (`SYSTEM`, an old e-mail actor) |
| `IncidentDtoMapper.employeeText` | the employee sees "Linked to an earlier report of the same problem" and "Passed to a better suited team" instead of the two entries above; the stored text is unchanged for staff |
| `app.js` (`tdActor`) and `index.html` | the admin dialog shows "Name (id)" for the override, the resolver and every audit actor; asset version 3.7 |
| `ArchitectureRegressionTest` | fails the build if a controller reads `Authentication.getName()` again (`AuthController` is the one exception, it needs the e-mail for `/me`) |

## How to check it yourself

- Automated: `mvn -o test "-Dtest=ActorIdentityRegressionTest,EmployeeHistoryPrivacyRegressionTest"`.
- Live (application running, seeded): `python scripts/live-checks/actors_check.py` (API checks plus a real-browser look at the
  admin dialog; add `--no-browser` to skip the browser).
- By hand: sign in as `admin@nimbusfinserve.com` / `Password123!`, open a resolved ticket from the Command Centre and read
  "Manual override by <name> (ADMIN-SYS-01)", "Resolved by <name> (TECH-...)" and the audit timeline.

## Behaviour changed on purpose

- Rows written from now on carry ids; rows written before keep their e-mail actors and show them as plain text (no name).
- An employee's history no longer says which ticket a report duplicates or why a technician handed a ticket back.
- A note's author name is always the author's real name.

## Tests

| Class | Tests | What it proves |
|---|---|---|
| `ActorIdentityRegressionTest` | 4 | over HTTP and by direct service calls: after assign, override, note, resolve, verify and feedback every stored actor is an id; names show in the staff views; an e-mail given to a service is turned into the id |
| `EmployeeHistoryPrivacyRegressionTest` | 1 | across every employee view of a seeded run: no other ticket's id, no technician reason |
| `EmployeeAuditTrailPrivacyTest` (+3) | 8 | the two entries are neutral whatever the stored text, other entries keep their text |
| `AdminIncidentDetailMapperTest` (+2), `AdminIncidentDetailServiceTest` (+1) | | the names reach the view; one batch lookup |
| `IncidentActorsTest` | 3 | which ids a view needs names for |
| `CurrentUserTest` | 4 | e-mail to id, id unchanged, unknown e-mail refused |
| `ActorNameMarkupTest` | 3 | the dialog uses the names, escapes them, asset version 3.7 |
| `ArchitectureRegressionTest` (+1) | | no controller reads the login name directly |

Changed on purpose: `AdminIncidentServiceOverrideTest` and `AdminIncidentServiceInputTest` (the mock administrator has an id),
`WrongDomainWorkflowRegressionTest` (new `addNote` signature), four markup tests (asset version).

Mutation checks (each made tests fail, then undone): note author, admin override actor and the architecture rule, acknowledge
and start-investigation event actors, note signing, resolver, override actor in the service, both employee texts, the name
lookup, the detail service's names, the mapper's override name, `CurrentUser` returning the e-mail. One more mutation (the
acknowledge time argument) survived because that argument is never stored, so it is not a behaviour.

## Known limits

- Old rows keep e-mail actors (a Flyway V8 conversion would only be needed if a production database holds them; D21 is
  unknown and assumed "no", see ADR-037).
- Notification and e-mail texts and the triage queue still use plain ids (CLAUDE.md section 7 item 14).
- Found on the way: tickets created by a test and left open became duplicate parents for another test class
  (`AutoAssignmentRegressionTest`); the new tests close their tickets.

## Verification

- Full `mvn -o clean verify`: 1179 tests (1157 before), 0 failures, 0 skipped, JaCoCo gate met.
- Every class in its own JVM: 152 classes, 1179 tests, 0 failures, 0 skipped.
- Live, against the running app (`scripts/live-checks/actors_check.py`): 11 of 11 checks (stored actors are ids, names in the admin dialog in a real browser, neutral employee history, no console error); no ERROR in the app log.
- Not run: CI on JDK 21.
