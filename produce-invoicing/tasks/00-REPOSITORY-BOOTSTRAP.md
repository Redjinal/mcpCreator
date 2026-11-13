# Task 00 — Audit and scaffold

## Instructions to Claude Code
First read `CLAUDE.md` plus documents 01–07, inspect the supplied v4 prototype (do not reuse its fake state as persistence), then complete the implementation below. Only edit files needed for this vertical slice. Add tests, run available build/test commands and log exact results. Stop after delivering the checkpoint; report any hardware/client-dependent blocker rather than simulating success.

## Implementation
Read all docs and reference ZIP. Inventory v4 views, interactions and data objects. Create Gradle Kotlin Compose Android project and module boundaries; set up Git and `IMPLEMENTATION_LOG.md`. Produce a traceability matrix mapping prototype screens -> actual screens, data layer, and unfinished functionality. Run build, record installed SDK/dependencies.

## Acceptance gate
Android app builds to debug APK; no dependency on prototype support.js; reviewed screen mapping and config documented.

## Report back
1. Changed files and architectural decisions.
2. Exact commands executed and pass/fail output summary.
3. Screenshot or UI test/physical printer evidence, when applicable.
4. Remaining P0 issues, client assumptions and recommended next task.
5. Update `IMPLEMENTATION_LOG.md` with dated checkpoint.

## V2 implementation amendment — approved client scope
**Implementation additions:** Provision server skeleton and Android build flavors for configurable LAN URL. Prepare docker compose for local API/PostgreSQL (no credentials checked in), compose healthcheck, secure seed fixtures, versioned API schema; wire `docs/08–10` as binding docs.

**V2 acceptance evidence:** Android debug build and server boot instructions; `docker compose` with safe env template; no cloud dependency; initial backup/restore runbook.

Read `docs/08–10` before coding; treat this section as overriding any earlier narrower scope. Record automated test output, manual-device results and blockers in `IMPLEMENTATION_LOG.md`.
