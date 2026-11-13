# Task 06 — Print interfaces and layouts

## Instructions to Claude Code
First read `CLAUDE.md` plus documents 01–07, inspect the supplied v4 prototype (do not reuse its fake state as persistence), then complete the implementation below. Only edit files needed for this vertical slice. Add tests, run available build/test commands and log exact results. Stop after delivering the checkpoint; report any hardware/client-dependent blocker rather than simulating success.

## Implementation
Build printer abstraction and fake/test printer; implement print snapshot formatter and PDF fallback; persisted attempt log, error/uncertain/reprint-as-COPY flow. Add printer setup screen. **Do not claim hardware support in this task.**

## Acceptance gate
Golden print-format tests, print attempt persistence, failure-state UI, accurate copy indicator, no duplicate invoice on print retry.

## Report back
1. Changed files and architectural decisions.
2. Exact commands executed and pass/fail output summary.
3. Screenshot or UI test/physical printer evidence, when applicable.
4. Remaining P0 issues, client assumptions and recommended next task.
5. Update `IMPLEMENTATION_LOG.md` with dated checkpoint.

## V2 implementation amendment — approved client scope
**Implementation additions:** Build configurable 58 mm renderer with profile-defined printable width/dots/encoding; `PrinterGateway` plus fake and distinct transports for SPP, TCP and vendor SDK registration. Profile selector, test print, COPY, persisted attempt and uncertain state; provide compatibility-matrix scaffolding.

**V2 acceptance evidence:** Golden receipts for 1/8/30 lines and long descriptions; unsupported glyph fallback; no phantom print acknowledgment; distinct profiles selected without rewriting invoicing code.

Read `docs/08–10` before coding; treat this section as overriding any earlier narrower scope. Record automated test output, manual-device results and blockers in `IMPLEMENTATION_LOG.md`.

## V3 field on receipts
Primary printed invoice number is `PRE-INV-SUF`, not the central `PRE-N`; driver name printed separately. Unregistered offline invoices must print normally. Internal register is optional and specifically labeled as internal if included.
