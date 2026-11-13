# Task 03 — Responsive field UI

## Instructions to Claude Code
First read `CLAUDE.md` plus documents 01–07, inspect the supplied v4 prototype (do not reuse its fake state as persistence), then complete the implementation below. Only edit files needed for this vertical slice. Add tests, run available build/test commands and log exact results. Stop after delivering the checkpoint; report any hardware/client-dependent blocker rather than simulating success.

## Implementation
Rebuild v4 experience as adaptive Compose screens: navigation, login stub behind auth interface, dashboard, customers/products, invoice lines/units, review/issue, invoice list/detail and owner shell. Use real Room data and stateflows.

## Acceptance gate
Phone/tablet portrait/landscape workable; all primary flows connected to real local persistence; no fake success indicators.

## Report back
1. Changed files and architectural decisions.
2. Exact commands executed and pass/fail output summary.
3. Screenshot or UI test/physical printer evidence, when applicable.
4. Remaining P0 issues, client assumptions and recommended next task.
5. Update `IMPLEMENTATION_LOG.md` with dated checkpoint.

## V2 implementation amendment — approved client scope
**Implementation additions:** Add 58 mm print preview, printer selector and diagnostics; invoice PAID/UNPAID and method input with clear no-gateway disclaimer; quick stock availability/shift context; offline warnings when allocated stock insufficient per approved policy.

**V2 acceptance evidence:** Phone/tablet issue flow uses stored stock and payment status, no fake print/sync success labels; invoice price snapshot unchanged.

Read `docs/08–10` before coding; treat this section as overriding any earlier narrower scope. Record automated test output, manual-device results and blockers in `IMPLEMENTATION_LOG.md`.

## V3 change — display (binding)
Use customer-facing `PRE-INV-SUF` everywhere as the primary invoice number; separate driver name and optional labeled `Office reference (internal): PRE-N`. Filter/search both. Offline invoice view must not imply that central reference exists.
