# V3 — Numbering and Register Acceptance Test Matrix

Every test is automated where feasible, plus two real Android devices for final gate. Record command, environment, evidence and pass/fail in `IMPLEMENTATION_LOG.md`.

| ID | Scenario | Required outcome |
|---|---|---|
| N01 | Driver AB starts at 100 offline; driver FD starts at 100 offline | `BGI-100-AB`, `BGI-100-FD`, distinct UUIDs; both valid |
| N02 | Both sync to office LAN | Two server invoices, distinct `BGI-N` internal refs; originals unchanged |
| N03 | Sync FD first although AB issued earlier | FD gets earlier central ref; AB retains earlier issue timestamp; no retrospectively reordered numbering |
| N04 | Simultaneous sync of 30 invoices from two clients | One atomic register sequence per accepted invoice, no duplicate central references |
| N05 | Replay same invoice/event 10 times | Exactly one server invoice, one register ref, one stock SALE_DEBIT per line |
| N06 | Same customer-facing number submitted with different UUID | Explicit NUMBER_COLLISION; no auto-renumbering or silent overwrite |
| N07 | App killed mid-issue / immediately after commit | No duplicate issued number; durable invoice + outbox + stock movement are atomic |
| N08 | Driver issues across app restart / day boundary | Continues same per-driver sequence, no reset |
| N09 | Driver changes display name | Earlier invoices retain old driver name snapshot and numbers |
| N10 | Lost/replaced device, old offline lease returns | Fenced or quarantined; no two independently authoritative active issuers |
| N11 | User changes device clock backward 2 days | UUID and sequence stay correct; skew flagged; server register order unaffected |
| N12 | Invoice arrives after owner-approved close | Retains original shift; exception flagged; original central refs untouched |
| N13 | Invoice voided, then issue another | Original IDs reserved; next number increases; no recycling |
| N14 | Print before sync, then after sync, then copy | Same primary `PRE-INV-SUF` on all 58 mm outputs; copy clearly marked; no new sale |
| N15 | Search and export original + central reference | Both resolve same UUID, statuses/time fields coherent |
| N16 | Failed/rolled-back server registration, then retry | Single committed register reference; no phantom accepted invoice |
| N17 | Business B has its own driver AB #100 | Distinct business scoped identity, no cross-tenant leakage |
| N18 | Concurrent double tap on Issue | Exactly one issued invoice from one action token; other request blocked/idempotent |

**Pilot gate:** N01–N18 must pass or be explicitly classified blocked, with owner sign-off on any non-P0 exception. Never assert full support without real printer model tests and real phone/tablet tests.
