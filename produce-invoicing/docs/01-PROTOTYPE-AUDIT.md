# Prototype Audit — 2026-11-13

## Files inspected
`Produce Invoicing.dc.html`, `Produce Invoicing v2.dc.html`, `Produce Invoicing v3.dc.html`, `Produce Invoicing v4.dc.html`, `Invoice Document.dc.html`, `support.js`.

## Technical format
Each HTML embeds custom `x-dc` markup plus a `<script type="text/x-dc">` component extending `DCLogic`, loaded by `support.js`. Do **not** mistake it for a standalone React/Angular/Vue app. It contains sample arrays and mutable component state, with no observed persisted `localStorage`/IndexedDB/backend and no actual native printer API invocation.

## V4 present at UX/prototype level
- Driver/owner demo role switch; phone/tablet/desktop preview; navigation.
- Create, save/review/issue invoice flows; catalogue/customer pickers; custom lines; unit/quantity/price; zero-price confirmation and calculation examples.
- Invoice records, filters, daily review, customer and product CRUD screens.
- Editable business details and next-number preference.
- Save/sync demonstration states, offline scenario, retries/conflicts (simulations only).
- Printer picker / error / uncertain outcome / simulated print history / preview and reprint.
- QuickBooks manual-entry status, not integrated export/import.

## Gaps before the app can be used in the field
- Real durable relational DB and migration strategy; app reboot survivability.
- Real separate accounts and enforced authorization.
- Offline globally unique invoice numbering without cross-device collision.
- Reliable conflict-safe sync and central owner visibility.
- Android printer transport, vendor-specific printing, device diagnostics and printed-output proof.
- Barcode/receipt layout validation on 58/80mm paper or A4 (paper choice open).
- Secure auth, backup, restore, audit, retention, export.
- Automated tests, APK CI, device testing, onboarding and documentation.
- Client-approved workflows for tax, payments, discounts, returns, credit notes and signatures.

## Preserve
Visual hierarchy, compact field-sales interaction, searchable customers/products, invoice summary, prominent issue action, status semantics, clear print issue resolution, multipurpose phone/tablet layout.

## Improve
Touch target sizes >=48dp where reasonable, simpler flow for repeat customers, totals always visible, bigger quantity keypad, clear offline state, hard separation of invoice issuance vs printing, explicit stock/sync version used, easier reprint and last-printed timestamp, no demo controls in pilot build.
