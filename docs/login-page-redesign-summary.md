# Login page redesign (ADR-055)

Front end only. Nothing committed.

## What the user asked for
A login page like the mockup, with three corrections (the Swiftdesk logo instead of the product icon, no role dropdown, no "Forgot password?" link), then: "Remember me for 30 days" visible but only for show, no Self-Register row, and (last) no demo accounts list under the card.

## What the page is now
Swiftdesk logo, EMAIL ADDRESS and PASSWORD fields with icons, an eye button that shows or hides the password, a display-only "Remember me for 30 days" box, and a blue Sign In button with an arrow. Nothing is shown below the card: the demo accounts list ("Nimbus FinServe Corporate Structure", 8 one-click persona buttons) was removed entirely.

## Phases
| Phase | Work | Result |
|---|---|---|
| 1 | `static/assets/swiftdesk-logo.png`, `SwiftdeskLogoAssetTest` | served without a token; one mutation check (made private -> 401) |
| 2 | sign-in card markup in `index.html`; register toggle made null-safe in `app.js` | all element ids kept |
| 3 | marked `login-` block at the end of `styles.css`, scoped to `#login-card`, dark-theme variables | checked in Edge: light, dark, 375 px |
| 4 | `setPasswordVisible` in `app.js` | `scripts/live-checks/login_page_browser.py`: 26 of 26 |
| 5 | `LoginPageMarkupTest` (13), asset version `?v=5.6` (14 files), guard update in `TechnicianPortalMarkupTest`, ADR-055, this file | markup tests 179 of 179; four mutation checks |
| 6 | full `mvn -o clean verify`; Edge scripts on the running app | 2072 tests, 0 failures, 0 skipped, coverage gate met; `login_page_browser.py` 27 of 27, `mfa_browser.py` 0 failures, `action_buttons_browser.py` 36 of 36, `team_view_browser.py` all passed |

## Where things are
- Markup: `index.html`, the sign-in card (from `<!-- Sign In Card -->` to `<!-- Self-Registration Card`).
- Styles: `styles.css`, from `/* Login page (login-) */` to `/* End Login page */`.
- Script: `app.js`, `setPasswordVisible` next to `showAuthView`, the click listener in `setupEventListeners`, one call in `showMfaStep`; `loadPublicConfig` treats the demo container as optional and the demo button handler is gone.
- Tests: `LoginPageMarkupTest`, `SwiftdeskLogoAssetTest`; `TechnicianPortalMarkupTest` skips the card and the login CSS block.

## Mutation checks (each made exactly one test fail, then undone)
1. a role dropdown added to the card -> `removedThingsStayRemoved`
2. the password not hidden on sign-out -> `eyeButtonBehaviour`
3. an unscoped rule in the login CSS block -> `rulesAreScoped`
4. the register toggle without its null check -> `registerToggleIsNullSafe`

Phase 1 also made the logo public-access test fail by removing `/assets/**` from the public list.

## Known gaps
- No visible way to open the registration form (the form and API remain).
- "Remember me" does nothing.
- No one-click demo sign-in on the page (the header persona bar in demo mode remains); the unused `.demo-pill` CSS rules were left in place.
- `LoginPageMarkupTest` now has 13 checks (one added for the removed demo list).
- No password reset exists, so there is no link for it.
- The two-step verification screen was not clicked through in the browser for this change (`mfa_browser.py` is planned for Phase 6).
- `CLAUDE.md` is not in the repository any more (removed by the history rewrite), so it was not updated.

## Scripts changed on the way (Phase 6)
- `action_buttons_browser.py` and `team_view_browser.py` clicked `#btn-toggle-register` to open the registration form; they now open it directly in the page, because the button is gone.
- `action_buttons_browser.py` looked for the administrator's triage cards with the technician-table helper `card()`, which can never match them (a stale script since ADR-051, not an application defect); it now uses a small `triage_card()` helper.
