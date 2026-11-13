# Task 01 — Domain models and arithmetic

## Instructions to Claude Code
First read `CLAUDE.md` plus documents 01–07, inspect the supplied v4 prototype (do not reuse its fake state as persistence), then complete the implementation below. Only edit files needed for this vertical slice. Add tests, run available build/test commands and log exact results. Stop after delivering the checkpoint; report any hardware/client-dependent blocker rather than simulating success.

## Implementation
Implement domain models for business/customer/product/prices/invoice/lines/driver sequences and internal register mapping/print attempts/audit/outbox. Explicit BBD cents, BigDecimal quantity and rounding rules. Draft and issued invoice state machine, immutable snapshots and business validation.

## Acceptance gate
Unit tests pass for decimal quantities, line rounding, invalid units/negative quantity, zero price confirmation, quantity changes, immutable issued values.

## Report back
1. Changed files and architectural decisions.
2. Exact commands executed and pass/fail output summary.
3. Screenshot or UI test/physical printer evidence, when applicable.
4. Remaining P0 issues, client assumptions and recommended next task.
5. Update `IMPLEMENTATION_LOG.md` with dated checkpoint.

## V2 implementation amendment — approved client scope
**Implementation additions:** Define `PaymentStatus`, `PaymentMethod`, `PaymentEvent`, `StockMovementKind`, `ReturnDisposition`, `ShiftStatus`, `QuantityBaseUnit`, `PrinterProfile`; decimal unit conversions (per-product versioned) and exact invoice totals. Prevent partial payment processing in MVP; never use payment amount as added sales.

**V2 acceptance evidence:** Test paid vs unpaid, correction audit, line quantity -> base stock quantity with known conversion, rounding and stock accounting 20-5-1=14 saleable excluding quarantined return.

Read `docs/08–10` before coding; treat this section as overriding any earlier narrower scope. Record automated test output, manual-device results and blockers in `IMPLEMENTATION_LOG.md`.

## V3 change — identity model (binding)
Implement pure domain type for `businessCode`, immutable `driverCode`, per-driver `sequence`, UUID and `customerInvoiceNumber = PRE-INV-SUF`, plus optional server-owned `registerSequence` / `registerReference = PRE-N`. Do NOT implement globally partitioned number blocks. Display strings are derived, persisted as immutable snapshots, and validated; add tests for `BGI-100-AB` vs `BGI-100-FD`, UUID uniqueness, invalid codes, overflow and zero/negative counters. Include three timestamps and driver name snapshot. Read `docs/11` and `docs/12`.
