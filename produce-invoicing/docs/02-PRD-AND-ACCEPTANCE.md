# Product Requirements and MVP Definition

## Actors
- Owner/Admin: manage customer/product/price master, driver assignments, company settings, view/export all invoices, audit and daily reconciliation, perform privileged void/correction.
- Driver: authenticate, obtain morning catalogue, create/issue invoices, print/reprint, view own records and sync state; cannot arbitrarily change master catalogue or issued financial records.

## In scope (P0)
- Onboarding: device registration, user login/PIN with secure local session and expiry policy.
- Product catalogue: name, SKU/ID, active flag, allowed unit variants, BBD unit prices, catalogue version; snapshot the price on each invoice line.
- Customer master: searchable customers with minimal contact/location fields; create customer offline with conflict handling.
- Drafts: persistent auto-save, add/delete lines, quantity, unit, unit price and zero-price validation; prevent nonsensical negatives; show exact BBD totals.
- Issue: atomic local commit, time and actor, unique number from allocated offline block, immutable snapshot.
- Records: search/filter today's and previous issued/voided invoices; distinguish issue, sync and print status.
- Printing: pair/configure supported printer, print diagnostic, physical invoice, reprint as COPY with event log; offline printing; PDF/OS print fallback.
- Sync: manual "Sync now" plus automatic on connectivity; retry after reboot; device/server idempotency; owner sees submitted invoices; incoming catalogue versions.
- Daily closing: per-driver sales, payment methods/status, stock loading/movements/physical count, variances, unsynced count and export/share CSV or PDF. Close is provisional until required records reach server.
- Audit: invoice issue/void, catalogue edits, role-sensitive changes, printing attempts, sync events.

## P1 only if pilot requires
- Driver-specific customer assignment, discounts, customer credit/credit-note workflows beyond basic returns intake, route/delivery note and signature capture.

## Out of initial pilot
- Live QuickBooks integration, tax filing, complex inventory valuation, multi-warehouse stock controls, card processing, multi-currency, iOS, full analytics, AI features.

## Acceptance scenarios (each must be demonstrated on real Android hardware)
A1: Turn off mobile data/Wi-Fi. Create customer, draft invoice with decimal weight, issue, reopen app -> identical invoice remains.
A2: Start two devices with separate driver sequences. Issue 25 invoices each while offline -> no duplicate invoice number.
A3: Printer switched off mid-print: invoice remains issued; status is uncertain/failed appropriately; recover and reprint is explicitly a copy.
A4: Repeated submission of same outbox item and server timeout -> exactly one server invoice.
A5: Catalogue price updates after one invoice: old invoice shows old rate; new draft uses approved new catalogue version.
A6: Driver login cannot read another driver's restricted records through API or local role switch.
A7: Kill app/reboot device while unsynced -> job remains queued and syncs later.
A8: Owner dashboard shows both drivers' uploaded invoices and daily totals; offline invoices remain visibly pending.
A9: Data export includes invoice IDs, totals and date; BBD rounding and arithmetic tests pass.
A10: APK installs, opens and works on both a real Android phone and tablet with no developer tooling attached.

## Definition of Done
No simulated success labels in real pilot mode; all P0 flows are persistent, tested, documented; printer support verified by model; installable APK and recovery guide delivered.

## V2 approved P0 expansion (overrides earlier P1 wording)
- Record configurable payment method and `UNPAID`/`PAID`, append-only settlement/correction events; no gateway or partial payments.
- Vehicle-level stock load, sale debit, quarantined returns, waste, physical count, variance and shift close with owner approval.
- Local office server synchronization; drivers may operate completely offline and sync on return to LAN.
- 58 mm thermal layout and multi-model printer architecture; model certification on actual hardware.
- Required acceptance: payment totals reconcile without double counting; movement ledger is idempotent and atomic; shift close cannot be final with unresolved uploads.
See docs 08–10 for contracts and acceptance details.

## V3 approved invoice-number acceptance (mandatory)
- A2 now specifically: Driver AB and FD each issue #100 offline, producing `BGI-100-AB` and `BGI-100-FD`; after sync both survive with distinct UUIDs.
- Server first-acceptance registration assigns e.g. internal `BGI-100` and `BGI-101` atomically, with both records searchable by either identifier.
- Registration-time order can differ from actual device issue-time; reports preserve both without rewriting already assigned central refs.
- Replays, late-arrival records, voids, device handoffs and clock skew must pass `docs/12-NUMBERING-TEST-MATRIX.md`.
