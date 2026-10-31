# Ticket Nomenclature - Phase 1: Domain Core

Scope: pure domain types for the new ticket id `DEPT-YEAR-SEQUENCE` (e.g. `FIN-2026-000142`). No existing behaviour changes: ticket ids are still generated as `INC-XXXXXXXX` until Phase 2. Decision record: ADR-013.

## What was built
| Class | Package | Pattern / OOP | Role |
|---|---|---|---|
| `TicketNumber` | `domain.model` | Value Object (record) | Holds department code, year, sequence; `format()`, `parse()`, `isValid()`. |
| `DepartmentCodeResolver` | `domain.policy` | Strategy / OCP | Interface: free-text department -> code. |
| `RegistryDepartmentCodeResolver` | `domain.policy` | Registry | Default implementation: 8 seeded mappings, runtime `register`, deterministic fallback. |
| `TicketSequenceRepository` | `domain.repository` | Port | `long next(code, year)`; implemented in Phase 2. |

## Rules enforced
- Code: 2-4 uppercase letters. Year: four digits. Sequence >= 1, zero-padded to six digits, never truncated (`OPS-2027-1234567` is valid).
- `parse` trims and upper-cases; rejects blank, legacy `INC-` ids, wrong widths, a zero sequence and a sequence that overflows `long` (all `ValidationException`).
- Resolver: registered name (case/whitespace-insensitive) -> its code; otherwise first three ASCII letters; fewer than two letters or blank/null -> `GEN`. Resolved codes are always legal for `TicketNumber`.
- Domain has no Spring/Jakarta imports (ArchitectureRegressionTest still green).

## Codes
Engineering ENG, Finance FIN, HR HR, Sales SAL, Operations OPS, Customer Support CSU, Legal LEG, Executive EXE.

## Tests added (39 cases, suite 262 -> 301)
- `TicketNumberTest`: format/padding, wide sequence, parse round-trip, forgiving parse, 10 malformed inputs, null/overflow, `isValid`, constructor invariants, equality.
- `RegistryDepartmentCodeResolverTest`: 8 seeded codes, normalisation, fallback, unusable input -> `GEN`, validity of every resolved code, runtime registration and override, registration validation.

## Known gaps (by design, later phases)
- Nothing uses these types yet; `IncidentFactory` still generates `INC-` ids (Phase 2).
- No persistence for the counters (Phase 2), no renaming of existing tickets (Phase 3), no priority display name (Phase 4), no UI change (Phase 5).
- The resolver is not yet a Spring bean; it will be wired in Phase 2.
- Two unregistered departments sharing their first three letters (e.g. "Customer Success" and "Customer Support" if unregistered) share a counter. Ids remain unique.

## Verification
`mvn verify` (Maven 3.10.0 from `~/.m2`, because the wrapper's 3.9.9 download was incomplete): 301 tests, 0 failures, JaCoCo gate met.
