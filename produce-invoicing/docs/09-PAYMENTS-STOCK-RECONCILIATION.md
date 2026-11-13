# Payments, stock, returns and end-of-day reconciliation

## Payment model: status is NOT invoice issue status
- `payment_status`: `UNPAID` or `PAID` (MVP). Display `UNPAID` by default. `payment_method`: `CASH`, `BANK_TRANSFER`, `CHEQUE`, `CARD_EXTERNAL`, `OTHER`, or `NONE`; company admin may configure visible methods.
- Record an append-only `payment_event` (UUID, invoice UUID, method, amount_minor, paid/unpaid transition, actor/device/time, external reference optional, idempotency ID). Never edit the invoice financial snapshot.
- MVP enforces full-amount one-method settlement only: `PAID` requires a single selected method and recorded settled amount equal to outstanding amount. `UNPAID` can have no method or a *declared expected method* if explicitly configured; no partial/mixed support yet. If correcting a mistaken PAID flag, create a reversing audit event; do not remove original event.
- Distinguish a manually recorded card/bank payment from independently verified settlement. No payment gateway, bank verification or storing cardholder data.
- Reports: issued gross, void totals, paid gross by method, unpaid gross, discrepancy/adjustment counts. Do not add paid receipts to issued sales totals (double count).

## Stock model
- Stock is measured per SKU + **stock unit** and location: warehouse and vehicle allocation. Build explicit conversion records for sell units vs base inventory units; never infer case/bag conversions from name.
- `stock_movement`: immutable event UUID, SKU, location, driver/vehicle, base quantity decimal, movement kind, business reference, actor, time, device, synchronization state. Types `OPENING_LOAD`, `TOP_UP`, `SALE_DEBIT`, `CUSTOMER_RETURN_IN`, `RETURN_TO_WAREHOUSE`, `SPOILAGE`, `COUNT_ADJUSTMENT`, `REVERSAL`.
- Movement snapshots and conversion versions are committed atomically with issued invoice & outbox. Issuing sale debits assigned vehicle stock exactly once using invoice UUID + line UUID unique reference. A duplicate sync/retry must not debit twice.
- A returned item is first `QUARANTINE/NOT_AVAILABLE` until disposition approved (resellable, spoilage, supplier return). Financial credit/refund remains separate and later requires authorized credit note.
- No silent update to opening load, counted balance or movement history; each adjustment includes reason and actor.
- Offline synchronization conflicts on vehicle stock are not resolved by last-write-wins. One assigned device per vehicle/driver per shift is recommended for pilot. Prevent overlapping shifts and duplicate loads; validate server side after upload.

## Shift/day lifecycle
`PLANNED -> LOADED -> OPEN -> CLOSING_PENDING_SYNC -> RECONCILED -> APPROVED` (plus `REOPENED_WITH_REASON`). Record local date and Barbados timezone, shift UUID, driver/device/vehicle.
- Owner prepares starting allocation/loads and validates online provisioning.
- Driver can start assigned shift offline using downloaded stock and price snapshot.
- Sales deduct base units; returns, spoilage and reloads add their own movement events.
- At closing, driver enters physical counts for each SKU. Expected = opening + top-ups - sold + eligible returned-to-vehicle - warehouse returns - spoilage + authorized adjustments; distinguish quarantined items from saleable quantity.
- Variance = physical count - expected; display both signed quantity and unit. Require reason on variance. Closing dashboard separately shows payment subtotals, outstanding invoices, printer issues, unsynced events.
- Driver may save provisional close offline. Owner final approval requires uploaded shift/invoice/payment/stock events, resolved conflicts, and explicit variance decision.

## Acceptance tests
P1: create unpaid cash-intent invoice, mark paid with Cash -> paid total rises but sales total unchanged.
P2: switch paid status via authorized correction -> both events audited, paid tally correct.
P3: cannot mark `PAID` with amount less than invoice total; block ambiguous mixed payments.
S1: load 20 units, sell 5, return 2 into quarantine, spoil 1 -> saleable 14, quarantined 2 (assuming return not released), with counted reconciliation.
S2: retry same SALE_DEBIT 10 times -> one movement, no double decrement.
S3: invoice persists if offline, stock debit is atomic, count survives app restart.
S4: two shifts cannot claim same vehicle allocation concurrently without owner resolution.
S5: owner can't approve final close when unsynced movement/events remain unresolved.

## V3 reconciliation identifiers
Use invoice UUID as the immutable foreign key for payment and inventory movements. Shift close and owner exports show both the issued `PRE-INV-SUF` and optional internal `PRE-N`; missing central refs mean not yet server registered. Never link movements by a bare driver sequence or by issue timestamp. Later-arriving invoice gets a new central register number but remains reportable against its original shift/issue date.
