# Start Claude Code — Copy/Paste

## One-time setup
Extract the V3 ZIP into a new folder, preserving `CLAUDE.md`, `README.md`, `docs/`, `tasks/`, and `reference/`. Open that folder in a terminal, run `git init` if needed and start `claude` in that directory. Install Android SDK/JDK and backend tooling as required; Claude must detect what is present and report missing dependencies, not pretend compilation succeeded.

## First message to paste

```text
Read CLAUDE.md, README.md, docs/01–12 and tasks/00-REPOSITORY-BOOTSTRAP.md. Inspect reference/Produce Invoicing Prototype.zip with Version 4 as the UX reference. Implement ONLY Task 00 and then STOP at its acceptance checkpoint. Follow V3 docs/11-INVOICE-NUMBERING-AND-REGISTER.md as authoritative: the permanent customer number is PRE-INV-SUF (e.g. BGI-100-AB), with independent per-driver counters; the office server later issues a separate internal PRE-N register reference (e.g. BGI-100) at first accepted sync. Do not use v2 global number blocks, do not simulate printer/sync success, and do not treat the prototype as native Android code. Create actual source and tests, run all available checks, record exact results and blockers in IMPLEMENTATION_LOG.md, and summarize changed files and next task for approval.
```

## After Task 00 passes

```text
Implement ONLY tasks/01-DOMAIN-AND-MONEY.md. Re-read CLAUDE.md and V3 docs/11–12. Add working code and tests, run checks and update IMPLEMENTATION_LOG.md. STOP after the acceptance gate; do not automatically proceed.
```

Then use the same wording for Tasks 02–14, one at a time. Tasks 04/05 implement real driver-sequence issuance and server registration; Task 14 is the final integration and test gate. **Task 13 is APK packaging; do not call the pilot accepted until Task 14 passes.**
