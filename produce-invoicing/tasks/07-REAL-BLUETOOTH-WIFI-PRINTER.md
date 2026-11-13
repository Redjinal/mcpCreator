# Task 07 — Hardware print integration

## Instructions to Claude Code
First read `CLAUDE.md` plus documents 01–07, inspect the supplied v4 prototype (do not reuse its fake state as persistence), then complete the implementation below. Only edit files needed for this vertical slice. Add tests, run available build/test commands and log exact results. Stop after delivering the checkpoint; report any hardware/client-dependent blocker rather than simulating success.

## Implementation
Inspect approved real printer model and manufacturer docs; determine Bluetooth SPP/BLE, ESC/POS/vendor SDK and Wi-Fi TCP/IPP specifics. Implement only confirmed adapter(s), permission prompts, discovery/pairing/diagnostic print. Run on real phone/tablet and physical printer. If hardware absent, report blocked and leave truthful adapter placeholder.

## Acceptance gate
Physical test paper on selected model with 1/8/30 lines; recovery tests after power loss and uncertain send; compatibility report signed off.

## Report back
1. Changed files and architectural decisions.
2. Exact commands executed and pass/fail output summary.
3. Screenshot or UI test/physical printer evidence, when applicable.
4. Remaining P0 issues, client assumptions and recommended next task.
5. Update `IMPLEMENTATION_LOG.md` with dated checkpoint.

## V2 implementation amendment — approved client scope
**Implementation additions:** Integrate confirmed 58 mm printers by capability/profile; Bluetooth Classic SPP, Wi-Fi TCP ESC/POS, BLE/vendor SDK only with documented support. Test two distinct models where provided and write per-model matrix; never certify a model from protocol marketing alone.

**V2 acceptance evidence:** Phone/tablet actual paper on each supplied certified model; long/currency print, reconnect, power loss and uncertain duplicate warning. If second model absent, mark support unverified and release restricted profile only.

Read `docs/08–10` before coding; treat this section as overriding any earlier narrower scope. Record automated test output, manual-device results and blockers in `IMPLEMENTATION_LOG.md`.
