# Task 10 — Payment method and paid/unpaid (P0)

## Read first
`CLAUDE.md`, `docs/08–10`, tasks 01–05 and 08. Inspect prototype v4 payment/closing flows, if any. Do not implement a payment gateway.

## Build
1. Local Room/DAO/repository models and migration for append-only payment events, enum values, status/expected vs settled method, optional external reference, actor/device/timestamps.
2. Invoice UI defaults to UNPAID. Provide configurable methods Cash/Bank Transfer/Cheque/Card-External/Other. Payment 'PAID' must record method and full settled amount (invoice total) and an event. Block unsupported partial/mixed settlement and never save card data.
3. Correction of erroneous payment state uses reversal event with reason and permission checks, not destructive update. Offline event queued using transactional outbox, server idempotent receipt and read model.
4. Driver own transactions, owner all drivers, reports by method and unpaid gross; sales totals remain independent.
5. Unit tests, Room migration/instrumentation tests, API retry tests, UI tests.

## Gate
Create invoice offline UNPAID -> mark paid CASH -> restart -> paid persists -> upload 10x -> one settlement. Reversal restores unpaid status and audit trail. Invalid method/amount blocked. Reconciled sales unchanged by settlement; no false 'paid by bank' verification.

## Report
Changed files, tests and exact commands, screenshots, unresolved client method configuration, log checkpoint, stop.
