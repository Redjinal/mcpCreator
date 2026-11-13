# Task 02 — Durable offline data

## Instructions to Claude Code
First read `CLAUDE.md` plus documents 01–07, inspect the supplied v4 prototype (do not reuse its fake state as persistence), then complete the implementation below. Only edit files needed for this vertical slice. Add tests, run available build/test commands and log exact results. Stop after delivering the checkpoint; report any hardware/client-dependent blocker rather than simulating success.

## Implementation
Implement Room entities/DAOs/migrations, repositories and transactional issue operation that writes invoice snapshot + audit + outbox atomically. Seed synthetic demo data. Persist drafts and guarantee app restart durability.

## Acceptance gate
Instrumented persistence tests; app restart retains issued/draft records; issue transaction rollback keeps consistent DB.

## Report back
1. Changed files and architectural decisions.
2. Exact commands executed and pass/fail output summary.
3. Screenshot or UI test/physical printer evidence, when applicable.
4. Remaining P0 issues, client assumptions and recommended next task.
5. Update `IMPLEMENTATION_LOG.md` with dated checkpoint.

## V2 implementation amendment — approved client scope
**Implementation additions:** Add Room migrations for payment events, shifts, locations, vehicle stock allocations, stock movement, return intake/quarantine, count/variance, printer profiles. Atomically issue invoice + stock debits + outbox; unique UUIDs and movement reference constraints.

**V2 acceptance evidence:** Process death cannot lose invoice/stock transaction; duplicate sale debit fails safely; pending payment and stock events survive reboot; database migration from earlier schema tested.

Read `docs/08–10` before coding; treat this section as overriding any earlier narrower scope. Record automated test output, manual-device results and blockers in `IMPLEMENTATION_LOG.md`.

## V3 change — local atomicity (binding)
Add Room table for fenced driver sequence and immutable business/driver code; atomic transaction must reserve next per-driver sequence, persist issued invoice + stock movements + audit + outbox exactly once. Crash/retry must not reuse an issued number. Local `registerReference` nullable and only set by validated server ACK; never mutate customer-facing number. Migration and constraints tests required. See docs 11/12.
