# Critical defect fixes from the stress audit (C1, C2)

Date: 2026-10-31 - see ADR-015.

## Changed
- `domain/model/Incident.java`: removed `sanitizeHtml`; title and description are stored as entered.
- `application/service/AdminIncidentService.java`: `req.approve(admin.getId(), now)` in `assign` and `acceptTriageSuggestion`.
- `IncidentEncapsulationTest`: the escaping test now asserts verbatim storage and no growth on rebuild.
- New `CriticalDefectsRegressionTest` (3 tests): repeated save/load keeps `R&D ... can't` unchanged; a 120-apostrophe title is storable; an admin (identified by email, as in production) can assign a wrong-domain ticket and the request is approved with `ADMIN-SYS-01`.

## Verified
`mvn verify`: 383 tests, 0 failures (was 380).

## Not changed
- C3 (duplicates and capacity) is by design (F4); the real problem is false duplicate clustering (H1), still open.
- High items H1-H10 and Medium/Low items from the audit are untouched.
- Existing persistent data that was stored escaped needs a one-off unescape.
