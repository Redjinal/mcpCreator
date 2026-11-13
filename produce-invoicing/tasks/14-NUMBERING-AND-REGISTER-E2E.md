# Task 14 — Final dual-numbering integration and regression gate (P0)

## Read first
`CLAUDE.md`, `docs/11-INVOICE-NUMBERING-AND-REGISTER.md`, `docs/12-NUMBERING-TEST-MATRIX.md`, and completed tasks 00–13. Review numbered identity behavior across Android Room, API, PostgreSQL, printer, payment, stock, reports and owner reconciliation.

## Implementation / verification
1. Ensure customer-facing `PRE-INV-SUF` is created offline per independently durable driver sequence. No remnants of globally non-overlapping number blocks in active code, migrations or client text.
2. Verify server assigns independent `PRE-N` only upon first accepted registration; transactionally and idempotently for same UUID, unique for different UUIDs; no MAX()+1 race.
3. Make every query, receipt (58 mm), report, export, print attempt, payment, return and stock movement join by UUID. Expose both numbers with clear labels, central ref nullable until synced.
4. Implement and execute all N01–N18 tests. Include real device print/UX tests where equipment is available; clearly flag hardware blockers.
5. Simulate issue-order vs sync-order disagreement, late sync after shift close, clock skew, collision with distinct UUID, repeated ACKs and lost-device handoff.
6. Document data migrations, schema constraints and recovery policy; supply readable evidence, log and screenshots.

## Acceptance
No duplicate customer number within business, no duplicate central register reference within business, and no duplicated server records/stock debits on retry. Two drivers independently issue #100 and server maps them to distinct register references. Customer-printed number never changes. Reconciliation does not silently reorder historical register references. All P0 acceptance tests passing or explicitly blocked with operator action.

## Report & STOP
Run available checks; write commands/results and blocking issues to `IMPLEMENTATION_LOG.md`. Do not claim pilot readiness until real hardware and stakeholder approval. Stop for review.
