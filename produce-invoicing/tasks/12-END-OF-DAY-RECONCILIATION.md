# Task 12 — Driver close and owner approval (P0)

## Read first
`CLAUDE.md`, `docs/08–10` and tasks 01–11.

## Build
1. Shift state machine PLANNED/LOADED/OPEN/CLOSING_PENDING_SYNC/RECONCILED/APPROVED and reasoned REOPEN.
2. Driver physically counts stock by SKU, keeps returned quarantined units distinct, enters waste/warehouse returns and explicit variance explanation. Show expected vs actual with unit, signed variance and all movements.
3. Close summary combines issued/voided totals, paid totals by method, unpaid totals, invoice count, return intake, print problems, unsynced outbox, physical inventory and adjustment trail. Avoid counting paid totals as new sales.
4. Driver can save provisional offline close; owner may only approve on server after all related outbox events uploaded and conflicts resolved, with reasoned exceptions if policy permits.
5. CSV export and summary view, owner audit/approval access checks, recovery on reopen.

## Gate
Demonstrate real shift with 3 invoices, 2 payments, 1 return, 1 spoilage and count variance; no duplicate totals; physical reconciliation math correct. Attempt owner approval while a stock event unsynced -> denied. Retry sync, resolve variance, approve -> signed/auditable close; driver approval rejected.

## Report
Changed files, screenshots, test output, client variance policies, log checkpoint, stop.

## V3 nightly reconciliation
Daily/shift reports must reference UUID and original issued invoice number. All uploaded records receive permanent internal register references in server-registration order; do not reallocate by sorting device timestamps at close. Late sync after approved close must trigger an exception/reopen or formal adjustment review, preserving original shift date. Test two invoices #100 on different drivers and a late arrival; owner view maps both without double counting sales, payments or stock.
