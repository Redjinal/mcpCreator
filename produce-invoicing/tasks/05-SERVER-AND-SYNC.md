# Task 05 — Minimal API and idempotent sync

## Instructions to Claude Code
First read `CLAUDE.md` plus documents 01–07, inspect the supplied v4 prototype (do not reuse its fake state as persistence), then complete the implementation below. Only edit files needed for this vertical slice. Add tests, run available build/test commands and log exact results. Stop after delivering the checkpoint; report any hardware/client-dependent blocker rather than simulating success.

## Implementation
Build minimal server/API PostgreSQL schema and migrations; bootstrap, device enrollment, provision fenced driver issuing leases, upload outbox with event UUID idempotency and unique constraints, receive catalogue updates and owner daily summary. Implement Android WorkManager backoff/manual sync and conflict UI. Document local server vs hosted endpoint.

## Acceptance gate
Repeated same event never duplicates invoice; process kill/reboot queue eventually syncs; owner sees driver invoices; stale catalogue never changes issued invoice.

## Report back
1. Changed files and architectural decisions.
2. Exact commands executed and pass/fail output summary.
3. Screenshot or UI test/physical printer evidence, when applicable.
4. Remaining P0 issues, client assumptions and recommended next task.
5. Update `IMPLEMENTATION_LOG.md` with dated checkpoint.

## V2 implementation amendment — approved client scope
**Implementation additions:** Implement office LAN host instead of cloud-hosted API. Add versioned sync envelopes for invoice, payment_event, stock_movement, return_intake, shift, stock_count, variance and approvals; transactional outbox, idempotency and per-vehicle single-shift lease. WorkManager sync when LAN endpoint reachable, not merely internet reachable. Document cloud migration contract and restore.

**V2 acceptance evidence:** Two devices operate offline and sync on return to LAN; 10x replay leaves exactly one invoice and stock debit; owner sees payments and vehicle stock; no exposure of Postgres to public Internet.

Read `docs/08–10` before coding; treat this section as overriding any earlier narrower scope. Record automated test output, manual-device results and blockers in `IMPLEMENTATION_LOG.md`.

## V3 central register specification (binding)
Implement server transaction `registerInvoice(invoiceUuid, customerInvoiceNumber, ...)` with UUID/event idempotency, UNIQUE `(business_id, customer_invoice_number)` and UNIQUE `(business_id, register_sequence)`. After business validation, atomically increment **business-level** register counter and insert invoice+mapping in one commit (transactional counter row lock or equivalent). On replay return already assigned `PRE-N`. A failed transaction must not create phantom invoice or a duplicate visible central reference; if register numbering must be gap-free for financial policy, define accepted rules for rolled back allocations and voided records. Return first_received_at_server, registered_at_server and central ref. Device time is not used to order central register. Collision same customer-facing number different UUID -> explicit conflict, no overwrite and no automatic renumber. Concurrency tests required.
