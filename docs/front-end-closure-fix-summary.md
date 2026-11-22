# Front-end closure (Phase J)

Remaining-defects plan, Phase J. ADR-039 (CLAUDE.md section 7 items 6 and 14). One back-end field added (`displayId` on
the triage queue entry); no schema change, no migration. Asset version 3.8.

## In plain words

The web screens were checked piece by piece, but nobody had ever pressed **every** button in a real browser. This phase did,
with a script that signs in as each kind of user, clicks the buttons and checks the result. It fixed the gaps the plan listed
and found two more that no earlier test could see.

| What | Before | After |
|---|---|---|
| Sign-in and registration | a second sign-in (demo pill, persona chip) or a second registration while one was running was sent too | the second one is ignored |
| A refused action (400, 403) | the list stayed as it was, showing a ticket the user could no longer act on | the list refreshes, as it already did for conflicts |
| Keyboard focus | a disabled button lost focus; dialogs let Tab wander out and did not return focus | focus returns to the button (or its redrawn twin); Tab and Shift+Tab stay inside the open dialog; focus moves into a dialog when it opens and back when it closes |
| Composite score | 2 decimals beside 3-decimal contributions | 3 decimals |
| Triage cards | only the plain ticket id | the staff name (priority and id) next to it |
| **Found by the run:** employee "Provide Requested Info" | **the button never appeared**: the card looked for the internal status `PENDING_INFO`, but employees only receive "IN PROGRESS". An employee could not answer a technician's question in the web app (the API worked). The "waiting for you" counter was always 0. | the buttons and the counter follow `allowedActions`, which the server sends |
| **Found by the run:** sign out after registering | the registration form was shown instead of the sign-in form | the sign-in form |

## How to check it yourself

- Automated structure checks: `mvn -o test "-Dtest=FrontEndClosureMarkupTest"`.
- The real thing (application running and seeded; Python with Playwright and Microsoft Edge):
  `python scripts/live-checks/action_buttons_browser.py` (add `--headed` to watch it). It prints one line per check and
  ends with `OK` or `FAILED`.
- By hand: sign in as `employee@nimbusfinserve.com`, report a ticket, sign in as its technician and click
  "Request Info", then sign in as the employee again: the card now offers "Provide Requested Info".

## Behaviour changed on purpose

- The employee screens decide which buttons to show from `allowedActions`, not from the status text.
- A refused write (400 or 403) refreshes the open tab.
- Signing out always lands on the sign-in card.

## Tests

| Class | Tests | What it proves |
|---|---|---|
| `FrontEndClosureMarkupTest` | 11 | the guards come first and are released in `finally`; the refresh condition; the focus code; the Tab trap; the dialog focus handling; employee buttons follow `allowedActions`; three decimals; the triage name; the sign-in card; asset version |
| `AdminIncidentServiceWrongDomainTest` (+1) | 11 | the triage entry carries `displayId` |
| `action_buttons_browser.py` (not part of Maven) | 33 checks | every action button in a real browser with the result confirmed through the API, plus the guards |

Changed on purpose: the markup tests that pin the asset version (3.8), `WriteGuardMarkupTest` (400 and 403 refresh),
`TicketDisplayNameMarkupTest` (one more staff use of `displayId`: the triage card).

## Known limits

- **Team lead "Acknowledge & Handle" on an unassigned ticket cannot work**: the server only lets the assignee acknowledge, so
  the lead gets HTTP 409 and the misleading message "This ticket was just updated by someone else". Giving leads a way to
  take an unassigned ticket is a new feature that needs a decision, so it was not built; an administrator or the dispatcher
  assigns such tickets.
- The multi-select menus still do not trap focus or remember their selection (CLAUDE.md section 7 item 11).
- Notification and e-mail texts for staff still use the plain ticket id.

## Verification

- Full `mvn -o clean verify`: 1212 tests (1200 before), 0 failures, 0 skipped, JaCoCo gate met.
- Every class in its own JVM: 159 classes, 1212 tests, 0 failures, 0 skipped.
- Real browser (`scripts/live-checks/action_buttons_browser.py`): 33 of 33 checks in Microsoft Edge (every action button of every role, the sign-in and registration guards, focus return, the Tab trap, the 403 refresh, no console error), no ERROR in the app log.
- Mutation checks (each broke the browser script, then was undone): sign-in guard removed, registration guard removed, 403
  no longer refreshing the list, Tab trap disabled, focus not restored (the first run of this one survived because the
  script only watched a flow that never redraws its list; the script now also drives the admin Override button by keyboard
  and the mutation is caught), the employee button following the internal status again, composite score back to two
  decimals, triage card without the staff name, sign-in page keeping the registration card.
- Not run: CI on JDK 21; Safari and Firefox (the run uses Microsoft Edge).
