# Task 11 — Vehicle stock, sales movements and returns (P0)

## Read first
`CLAUDE.md`, `docs/08–10`, tasks 01–05 and 08. Do not treat return as invoice edit or automatic refund.

## Build
1. Warehouse/vehicle locations; per-driver shift; admin-authorized opening load/top-up and stock allocation, per SKU base-unit conversion version, decimal quantity.
2. Append-only, idempotent movement ledger: load, top-up, sale debit, customer return quarantine, return to warehouse, spoilage, count adjustment, reversal. Unique linkage of each sale debit to invoice line.
3. Invoice issue transaction: immutable invoice snapshot + stock debit(s) + audit and outbox. No partial commit. Read-only saleable vehicle quantity shown to driver; policy for negative stock must be explicit and testable.
4. Return intake screen with invoice reference when available, item/quantity/condition/reason and quarantine disposition; owner can approve restock/disposal in separate event. Avoid inventing credit-note/refund logic.
5. Sync protocol verifies single authorized shift per vehicle/stock allocation and rejects conflicting movements for owner resolution; event replay doesn't double count.

## Gate
20 loaded, 5 sold, 2 returned into quarantine, 1 spoiled -> saleable 14, quarantined 2; restart and repeat upload 10x -> identical balances. Power failure during issue leaves both invoice and movements or neither. Driver cannot approve own stock adjustment; out-of-stock/negative policy enforced.

## Report
Changed files, tests/results, location/units policy, unresolved physical stock decision, log checkpoint, stop.
