# Feature summary: Multi-select Category and Priority filters (Admin Command Centre)

## What was built
The Category and Priority filters of the Enterprise Incident Directory changed from native single-select
`<select>` elements to custom multi-select dropdowns. An administrator can now view, for example, P1 and P3
tickets together, or NETWORK and STORAGE tickets together, and combine both filters (values inside one filter are
OR-ed, the two filters are AND-ed). Each option shows a hollow 16px checkbox; a selected option shows a blue
border with a solid blue dot. The trigger label reads "All Categories" / "All Priorities" with nothing selected,
the values (`P1, P3`) for one or two, and "3 selected" for more. The existing **Filter** button runs the search.

## Where each piece lives and why

| Layer | Piece | Role |
|---|---|---|
| Web | `AdminController#searchIncidents` | `priority` and `category` request params now bind to `Set<Priority>` / `Set<IncidentCategory>`. Still no logic in the controller. |
| Application | `SearchService.IncidentSearchCriteria`, `IncidentSearchPort.SearchCriteria` | Carry `priorities` and `categories` sets; null or empty means no filter. |
| Infrastructure | `IncidentSpecifications.hasPriorityIn` / `hasCategoryIn` | `IN` predicates (Pattern: Specification). The single-value specifications remain. |
| Infrastructure | `IncidentSearchAdapter` | Chains the two new specifications. |
| Frontend | `index.html` | `#multiselect-category` and `#multiselect-priority`; option values are the enum names. |
| Frontend | `styles.css` | Dropdown, menu overlay, trigger, options, `.custom-checkbox` and the blue dot. |
| Frontend | `app.js` section 15 | `initMultiSelect(containerId, options, onChange)`, `setupAdminFilterMultiSelects`, and the repeated-param query in `searchAdminIncidents`. |

Decision recorded in ADR-012. No class was added to `com.iitsm.domain`, so the PlantUML diagrams in `docs/uml/`
are unchanged.

## API contract
`GET /api/admin/incidents?priority=P1&priority=P3&category=NETWORK&category=STORAGE`
Repeated parameters are what the UI sends. A single value (`?priority=P1`) and the comma-separated form still bind,
and a blank value (`?priority=`) applies no filter, so existing callers keep working.

## Corrections to the original specification
- **`ACCESS` is not a category.** `IncidentCategory` is `HARDWARE, SOFTWARE, NETWORK, DATABASE, CLOUD, SECURITY,
  AUTHENTICATION, APPLICATION, STORAGE`. The old dropdown offered `ACCESS` (which made the request fail) and left out
  `AUTHENTICATION` and `STORAGE`. The new dropdown lists the nine real values, in enum order, and a test compares the
  markup with the enums.
- **`--accent-blue` does not exist.** The design system's blue is `--primary` (#0284c7); that is what the selected
  border and dot use. The unselected border is `#94a3b8` because `--border` is too faint for a checkbox outline.
- **Checkbox shape.** A rounded square (3px) was chosen so the control does not read as a radio button; the dot is a
  circle. Changing to a circular box is one CSS value.
- **Filtering trigger.** Toggling an option only updates the selection and the label (so several options can be picked
  while the menu stays open); the existing **Filter** button performs the search. `initMultiSelect` accepts an
  `onChange` callback for callers that want live filtering.

## Behaviour of the component
- Menu stays open while options are toggled; closes on an outside click, Escape (focus returns to the trigger) or Tab;
  opening one dropdown closes the other.
- Select All and Clear in the menu header.
- Keyboard: ArrowDown on the trigger opens the menu; arrow keys, Home and End move between options; Space or Enter
  toggles. ARIA: `aria-haspopup`, `aria-expanded`, `role="listbox"` / `option`, `aria-selected`,
  `aria-multiselectable`.
- Options come from the markup (`data-value`), or from an `options` array passed to `initMultiSelect`; dynamic labels
  are inserted with `textContent`.
- CSP-compliant: no inline handlers, no external resources. Cache-bust bumped to `?v=3.3`.

## Tests added (suite: 262 tests, `mvn verify` green, JaCoCo checks met, line coverage 85.5%)
- `IncidentSpecificationsTest` (+4): several priorities, several categories, null/empty applies no filter, both combined.
- `AdminControllerTest` (+1): repeated and single params for priority and category, exclusion, blank values.
- `MultiSelectFilterMarkupTest` (9, new): native selects gone, dropdown structure, option values equal the
  `IncidentCategory` and `Priority` enums, options start unselected, 16px hollow checkbox and 8px blue dot CSS,
  dropdown classes and z-index, cache-bust version, script wiring and repeated-param query.

Beyond the Maven suite, the work was verified in headless Edge. A harness ran the real script against the real markup
(30 checks: toggling, labels, outside click, Escape, Tab, keyboard, `onChange`, dynamic options, missing container).
A DevTools-protocol run against the live application with real mouse and keyboard input (27 checks, zero console
errors) confirmed the request URLs, that table rows and counts match the API for priority and category filters
alone and combined, the computed checkbox and dot styles, that the menus stay inside the viewport above the table,
and that the ticket detail dialog still works. Those browser scripts are not part of the repository.

## Known gaps
- An unknown enum value in a query parameter (for example `?priority=P9`) returns HTTP 500 instead of 400, because
  `GlobalExceptionHandler`'s catch-all handles the type-mismatch exception. This applied to the old single-value
  parameter too and affects every enum request parameter; it is not fixed here.
- The selection is not remembered across reload or sign-out, and does not search automatically on change.
- Select All sends every value, which returns the same rows as no filter.
- Menu focus is not trapped inside the open menu (Tab closes it instead).
- The browser verification scripts are not in the repository, so only the static contract test runs in CI.
