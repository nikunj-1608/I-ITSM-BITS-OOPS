# Employee tab: box alignment (ADR-053)

Date: 2026-11-21. Branch `bug-fixes`. Nothing committed (at the user's instruction).

## What was asked

The rectangular boxes of the Employee tab looked scattered. The request: arrange them systematically, with their
edges parallel, in blocks of related features, and **change nothing else**: not the matter inside the boxes, only their
position, size and spacing.

## What was wrong (measured, 1400 px wide)

| Problem | Before |
|---|---|
| Edges of one row did not continue into the next | The four KPI cards ended at x = 333, 679, 1026. The ticket column ended at 901 and the tracking box began at 922. No vertical edge lined up. |
| Ragged bottoms | The Live Tracking box ended 108 px above the ticket column (y 721 against 829). |
| Two spacing values | 14 px between the KPI cards, 21 px everywhere else. |
| Tracking box under the tickets on a tablet | At 1024 px the tracking box dropped below everything as its own row. |

A first theory, that the empty confirmation container (`#emp-callout-container`) doubled a gap, was checked and was
**wrong**: the margins of an empty block collapse, so it adds nothing.

## What was done (CSS only)

Only the Employee block of `src/main/resources/static/styles.css` changed (about 16 lines). **No HTML, no JavaScript,
no text, button, icon, colour, padding or font changed**, and the other tabs and the user's theme files are untouched.
The asset version in `index.html` went from `?v=5.1` to `?v=5.2` so browsers load the new CSS.

| Phase | Change |
|---|---|
| 0 | Baseline screenshots and measured edges (nothing changed). |
| 1 | One spacing value, `--emp-gap` (1.5rem), on `#console-employee`; the section rows, the left column, the four KPI cards and the ticket area all use it. The KPI gap went from 14 to 21 px. |
| 2 | `.emp-layout` is a 4-column grid like the KPI row. The tickets span columns 1 to 3 (`.emp-main`, a flex column whose last card grows), Live Tracking is column 4, and both end at the same height. |
| 3 | Between 1025 and 1199 px the columns are narrow, so tickets and tracking take two columns each. At 1024 px and below the boxes stack at full width, as before. |
| 4 | This document, ADR-053, the asset version, the re-recorded stylesheet hash, tests. |

### Layout by width

| Width | Layout |
|---|---|
| 1200 px and up | KPI cards 4 x 1 column; tickets 3 columns, tracking 1 column |
| 1025 to 1199 px | KPI cards 4 x 1 column; tickets 2 columns, tracking 2 columns |
| 641 to 1024 px | KPI cards 2 x 2; tickets and tracking stacked at full width |
| 640 px and below | everything in one column, same order |

The privacy banner and the report form span the full width at every size, so their edges match the KPI row.

## Measured after (1400 px wide)

- KPI cards 327 px wide, 21 px gaps; the tracking box occupies exactly the fourth KPI column (x 1045 to 1372) and the
  ticket column ends exactly where the third KPI card ends (x 1024).
- The tracking box and the ticket column have equal tops and bottoms.
- Banner, KPI row and report form share the same left and right edges at every width.

## How it was verified

- Real browser (Microsoft Edge, Playwright): 13 widths from 390 to 1500 px, every ticket selected in both the Active
  and History views (39 scenarios, 0 failures): box edges on the KPI column lines (3+1 or 2+2), equal tops and
  bottoms, stacked gap exactly 21 px, no sideways scroll from 700 px up, nothing inside the tab beyond the screen
  at 640, 480, 390 and 360 px.
- Light and dark mode screenshots before and after.
- The diff contains only CSS lines, so the text inside the boxes cannot have changed.
- Tests: the `*MarkupTest` classes (see the change log in `CLAUDE.md` for the result).

## Tests changed on purpose

- `TechnicianPortalMarkupTest`: the recorded SHA-256 of `styles.css` outside the Technician block was re-recorded
  (`6b69dd26...`), because the Employee block is part of what it fingerprints. The new value was computed with the
  test's own rule, which first reproduced the old recorded value on the committed file.
- The 13 markup tests that pin the asset version now expect `?v=5.2`.

## Known limits

- When the tracking box is shorter than the ticket column, the extra height shows as empty space at the bottom of that
  box (equal bottoms was the request); when it is taller, the ticket list box stretches instead.
- At 1025 to 1199 px the ticket column is narrower than before (two columns instead of two thirds).
- At 640 px the page still scrolls sideways by about 28 px: the cause is the header's button row (outside this tab,
  probably the light/dark toggle), left alone on purpose.
- Only Microsoft Edge was used; the CSS uses grid and flexbox only.
- The scratchpad screenshots and the measuring scripts are not in the repository.
