# Task 08 — Operational review

## Instructions to Claude Code
First read `CLAUDE.md` plus documents 01–07, inspect the supplied v4 prototype (do not reuse its fake state as persistence), then complete the implementation below. Only edit files needed for this vertical slice. Add tests, run available build/test commands and log exact results. Stop after delivering the checkpoint; report any hardware/client-dependent blocker rather than simulating success.

## Implementation
Implement admin product price publication, customer maintenance, company settings and two-driver daily totals; CSV export; privileged void event with required reason; audit viewer for owner. Ensure price snapshots do not back-edit invoices.

## Acceptance gate
Daily counts/totals reconcile with issued/void statuses; export matches DB; driver forbidden from changing catalogue and voiding.

## Report back
1. Changed files and architectural decisions.
2. Exact commands executed and pass/fail output summary.
3. Screenshot or UI test/physical printer evidence, when applicable.
4. Remaining P0 issues, client assumptions and recommended next task.
5. Update `IMPLEMENTATION_LOG.md` with dated checkpoint.

## V2 implementation amendment — approved client scope
**Implementation additions:** Add master-stock and vehicle-load administration, owner payment-method configuration, paid/unpaid summaries, return approvals and stock adjustments. Exports include payment methods, invoice status, stock-movement and count/audit IDs.

**V2 acceptance evidence:** Owner dashboards totals match audited event-ledger, not double counting payments as extra sales; owner cannot silently edit movement history.

Read `docs/08–10` before coding; treat this section as overriding any earlier narrower scope. Record automated test output, manual-device results and blockers in `IMPLEMENTATION_LOG.md`.

## V3 owner register
Owner invoice search/export supports UUID, original `PRE-INV-SUF`, internal register `PRE-N`, driver code and driver name. Display `issued_at_device`, `registered_at_server`, sync status and clock skew; central register sorted by server registration sequence and shifts optionally sorted by original issue time. Flag late arrivals; never silently reorder or backdate the register number.
