# Task 04 — Identity and offline number allocation

## Instructions to Claude Code
First read `CLAUDE.md` plus documents 01–07, inspect the supplied v4 prototype (do not reuse its fake state as persistence), then complete the implementation below. Only edit files needed for this vertical slice. Add tests, run available build/test commands and log exact results. Stop after delivering the checkpoint; report any hardware/client-dependent blocker rather than simulating success.

## Implementation
Add authenticated provisioning interface and server-enforced roles; unique device/user enrollment; offline independent per-driver counter with fenced single-device issuance lease. Disallow duplicate/expired/revoked leases with explicit error and handoff. Prevent driver modifications to owner-only data.

## Acceptance gate
Two devices offline issue without colliding; driver cannot access owner API; numbers never change after issue.

## Report back
1. Changed files and architectural decisions.
2. Exact commands executed and pass/fail output summary.
3. Screenshot or UI test/physical printer evidence, when applicable.
4. Remaining P0 issues, client assumptions and recommended next task.
5. Update `IMPLEMENTATION_LOG.md` with dated checkpoint.

## V2 implementation amendment — approved client scope
**Implementation additions:** Permissions: owner authorizes loads, return disposition, variance resolution and closing; driver issues, records allowed payment methods, counts stock and provisional closes only. Provision exclusive driver-code issuing leases and stock allocations on LAN before departure.

**V2 acceptance evidence:** Driver cannot approve closing, alter master stock, edit issued invoices, or clear financial events via UI/API; revoked or conflicting driver issuing lease blocks issuing.

Read `docs/08–10` before coding; treat this section as overriding any earlier narrower scope. Record automated test output, manual-device results and blockers in `IMPLEMENTATION_LOG.md`.

## V3 gate — supersedes any earlier number block instructions
Driver `AB` and `FD` each produce invoice `100` offline as `BGI-100-AB` and `BGI-100-FD`. Unique driver code per business. Client increments atomically; issuing lease fences concurrent duplicate device use; handoff/reprovision includes reconciliation, counter resume, and conservative block if unsynced continuity cannot be established. Test across app restart, shift boundary, and rapid concurrent taps. Never pre-reserve a globally unique sequence block.
