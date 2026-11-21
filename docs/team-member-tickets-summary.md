# Team Lead: current tickets of a team member (ADR-054)

Date: 2026-11-21. Branch `bug-fixes`. Nothing committed (at the user's instruction).

## What was asked
On the Team Lead tab, clicking a technician should show all of that person's tickets: id, name, type and progress.
Only current tickets (not yet closed by the employee). Read only: no reassigning from this view. The upper part of the
console must look as before, the existing Assign and Reassign buttons stay as they are, and no backend code changes.

## What was wrong
The roster card was a plain box (`renderTeamRoster`, no click handler). The two lists below show unassigned and at-risk
tickets only, so an assigned ticket that is on track appeared nowhere, and finished tickets were filtered out.

## What was built (front end only)
| File | Change |
|---|---|
| `static/app.js`, section 8 `renderTeamRoster` | The card keeps its markup and gains `tlm-card`, `role="button"`, `tabindex="0"`, `data-id`, `data-name`, `aria-haspopup`, `aria-label`. |
| `static/app.js`, `handleSignOut`, `setupEventListeners` | One line each: close the window on sign-out; register the listeners. |
| `static/app.js`, new section 20 | `openTeamMemberTicketsModal` (GET team queue, filter by `assigneeId`, drop `VERIFIED` and `CLOSED`), the `tlm*` render helpers, `setupTeamMemberListeners` (click, Enter and Space, Escape, backdrop). |
| `static/styles.css` | A `tlm-` block at the end: pointer and focus ring on the card, a wider dialog (`:has(.tlm-dialog)`), ticket rows, progress bar. |
| `static/index.html` | Asset version `?v=5.3` only. |

No Java, schema, endpoint, DTO or event change. The Assign, Reassign and Inspect Escalation buttons are untouched.

## What the window shows
Title "Current tickets: NAME" with a cross button and a footer Close button (also Escape, a click outside, sign-out). Per
ticket: id and staff display name, priority and status chips, title, type, raised date, requester, SLA line, a five-step
progress bar (`RESOLVED` reads "waiting for the employee to confirm", `PENDING_INFO` "waiting for the employee to
reply") and a collapsible description, notes and resolution. Empty, loading and error states are shown.

## Tests
- New `TeamMemberTicketsMarkupTest` (11): clickable card, card look unchanged, read only (one GET, no write method, no action button, only the cross and Close buttons), the current-ticket filter, row content, collapsible details, escaping and no inline handlers, every way to close, late-answer guard and states, styles.
- Changed on purpose: `TechnicianPortalMarkupTest` (Team Lead script and `styles.css` hashes re-recorded), 13 tests that pin the asset version (`?v=5.3`).
- Edge: `scripts/live-checks/team_member_tickets_browser.py`, 28 of 28 (every roster card compared with the API, assigned, resolved and confirmed tickets, read-only requests, every way of closing, focus return, sign-out, no console error).

## Known limits
A ticket a member passed on no longer names them; a ticket held by skilled overflow outside the lead's categories is not in the lead's queue; the window is not refreshed while open; "time left" uses the browser clock; the server still permits a lead to reassign (only this window is read only); browser runs use Edge only.
