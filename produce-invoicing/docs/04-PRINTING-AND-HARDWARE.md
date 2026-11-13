# Printing, Bluetooth and Wi-Fi Hardware

## Printer selection is a gate
Record make, model, firmware, paper width (58mm/80mm/A4), protocol, Bluetooth mode (Classic SPP/BLE), OS print support, SDK/license, and Wi-Fi mode (LAN TCP/IP / vendor SDK / IPP). "Bluetooth printer" is not a sufficient specification. Buy/borrow a test unit before declaring P0 complete.

## Recommended strategy
`PrinterGateway` interface with `discover()`, `connect(config)`, `diagnosticPrint()`, `printInvoice(snapshot, copyMode)`, `status()`. Implement `FakePrinterGateway` in tests; one real adapter for the **selected** printer first. Where the confirmed hardware supports ESC/POS, generate bytes for correct paper width/encoding and send over vetted Bluetooth Classic RFCOMM/SPP or Wi-Fi TCP. Where hardware requires vendor SDK, implement vendor adapter instead. Support Android Print Framework PDF as a distinct fallback for compatible printers; do not assume generic Android print can control any thermal Bluetooth printer.

## Android permissions
Android 12+ Bluetooth runtime permissions include `BLUETOOTH_CONNECT` for paired devices, `BLUETOOTH_SCAN` if discovering devices. Handle older API versions per current Android documentation. Avoid blanket location access when not needed; ask permissions contextually. Wi-Fi local-network access and protocol limitations must be checked against the selected target SDK.

## Print workflow
1. Invoice is durably issued first.
2. Build printable snapshot with **same** invoice number/totals as stored, and clear copy indicator for reprints.
3. Persist a print attempt BEFORE sending bytes. Keep timeout/connection/write/possible-printed statuses separate.
4. On uncertain outcome, explicitly warn: "It may already have printed. Check paper before reprinting." User confirmation or device acknowledgement resolves the attempt.
5. Reprint logs operator, timestamp, printer ID and reason. Reprints cannot create or renumber invoices.

## Layout checks
Business identity, invoice number, local date/time with timezone, customer, product description, unit/quantity/unit price/line value, subtotal, approved tax fields, total BBD, footer and clearly marked COPY on duplicates. Long product names, decimal weights, currency symbol, accented names, paper cut and line wrapping. 58mm printer font and columns must be tested with sample invoices of 1, 8 and 30 lines.

## Hardware test matrix
BT connect / disconnect / re-pair; Wi-Fi IP change; same LAN vs hotspot; printer power loss; paper out; 5 repeated prints; app killed while printing; print from airplane mode; switching printer; phone and tablet. Record expected vs actual, photo of printed output (with synthetic data), firmware and adapter used.

## Blockers
Physical model and paper format remain unconfirmed; do not claim real BT or Wi-Fi printing is validated until device-specific testing passes.

## V2 fixed client choices
MVP uses **58 mm thermal roll**. Target compatible **multiple printer models** via profile and adapter registry; do not generalize compatibility from one model's successful ESC/POS print. Document 58 mm printable dot width, encoding, and alignment per model. Each printer profile is certified separately using real device and 1/8/30-line sample; paper width alone does not identify protocol. See docs/10 for matrix. Mark the 2-model pilot acceptance conditional on obtaining hardware; a one-model result is explicitly restricted compatibility.

## V3 receipt identifiers
On every 58 mm receipt prominently print immutable customer-facing invoice number `PRE-INV-SUF`, e.g. `Invoice: BGI-100-AB`, plus driver name separately. Do not print the internal register reference as a replacement invoice number. After server sync, if shown at all, label it `Office reference (internal): BGI-100`. Reprints must preserve the same customer-facing number; mark `COPY`, no re-issue or new stock debit.
