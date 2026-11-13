# Task 09 — QA/security and recovery

## Instructions to Claude Code
First read `CLAUDE.md` plus documents 01–07, inspect the supplied v4 prototype (do not reuse its fake state as persistence), then complete the implementation below. Only edit files needed for this vertical slice. Add tests, run available build/test commands and log exact results. Stop after delivering the checkpoint; report any hardware/client-dependent blocker rather than simulating success.

## Implementation
Add automated tests, secure token storage, input limits, scoped backend permissions, privacy sanitization, DB migration and recovery tests, offline + 1000 outbox load tests. Test phone/tablet orientation, accessibility, real printer and network recovery.

## Acceptance gate
Unit/integration/instrumentation logs; security test matrix and documented results; zero P0 regressions.

## Report back
1. Changed files and architectural decisions.
2. Exact commands executed and pass/fail output summary.
3. Screenshot or UI test/physical printer evidence, when applicable.
4. Remaining P0 issues, client assumptions and recommended next task.
5. Update `IMPLEMENTATION_LOG.md` with dated checkpoint.

## V2 implementation amendment — approved client scope
**Implementation additions:** Test secure LAN onboarding and credentials, Wi-Fi disconnect/reconnect, server outage/restart, Android power killing, backups, data migration, printer permission denial, duplicate payments, stock replay, quarantined returns, variance approvals.

**V2 acceptance evidence:** No lost/duplicated events; server restore yields consistent invoices, paid totals and stock ledger; device works offline through entire shift; unauthorized device cannot fetch another driver records.

Read `docs/08–10` before coding; treat this section as overriding any earlier narrower scope. Record automated test output, manual-device results and blockers in `IMPLEMENTATION_LOG.md`.
