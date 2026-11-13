# Task 13 — Build and pilot delivery

## Instructions to Claude Code
First read `CLAUDE.md` plus documents 01–07, inspect the supplied v4 prototype (do not reuse its fake state as persistence), then complete the implementation below. Only edit files needed for this vertical slice. Add tests, run available build/test commands and log exact results. Stop after delivering the checkpoint; report any hardware/client-dependent blocker rather than simulating success.

## Implementation
Build debug APK, provide SHA-256, installation steps, fake-data smoke script, driver and owner quick guides, backend setup/runbook, printer configuration instructions, issue register, rollback/restore plan and pilot reconciliation checklist.

## Acceptance gate
Independent tester installs APK on phone/tablet, creates/prints/reopens/syncs invoice, verifies owner daily summary; all open hardware/client blockers stated.

## Report back
1. Changed files and architectural decisions.
2. Exact commands executed and pass/fail output summary.
3. Screenshot or UI test/physical printer evidence, when applicable.
4. Remaining P0 issues, client assumptions and recommended next task.
5. Update `IMPLEMENTATION_LOG.md` with dated checkpoint.

## V2 pilot release gate
This task is now **Task 13** (superseding original task 10). Include delivery of LAN server deployment/backup instructions, printer compatibility matrix for each real supplied 58 mm model, invoice + payment/stock/return reconciliation examples and device enrollment procedure. On 5–10 pilot business days, run paper parallel and reconcile invoice counts, settlement methods and payment status, loads/movements/returns/counts, unsynced incidents and printer outputs. Restricted release is mandatory if only one printer model has been physically certified; do not describe multi-model support as tested until it is. No release with missing event idempotency, unresolved stock discrepancies, unreviewed tax/invoice layout, or data-loss defects.

## V3 pilot release gate
Do not mark client pilot complete until Task 14 numbering E2E passes (two driver 100s offline, server registration concurrency, retries, clock skew, late arrivals, voids, device transfer) and both fields print/search/export correctly. Explain that `PRE-N` is an internal register tracking number, not a replacement tax/customer invoice.
