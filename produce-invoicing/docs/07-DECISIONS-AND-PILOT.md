# Client Decisions and Pilot Plan

## Decisions to confirm before physical release
| ID | Decision | Provisional default | Why it matters |
|---|---|---|---|
| D01 | Printer manufacturer/model, Bluetooth type, Wi-Fi protocol, paper width | Multiple compatible models; 58 mm mandatory; hardware certification remains model-specific | Determines SDK and implementation |
| D02 | Device fleet and Android minimum OS | Android phones + Samsung tablets, min API 26 subject to device inventory | Test target and permissions |
| D03 | Does each driver have own login/device? | Yes, unique driver and device ID | Accountability/numbering |
| D04 | Printed legal invoice fields and tax/VAT applicability | Tax configurable but disabled until approved; no invented VAT rate | Compliance/financial math |
| D05 | Invoice numbering style | Per-driver `PRE-INV-SUF` sequence plus server-assigned internal `PRE-N` register; see docs/11 | Offline uniqueness |
| D06 | Price overrides/discounts | Driver cannot override catalog price unless authorized | Revenue controls |
| D07 | Invoice voids, returns, credit notes | Owner void with reason; stock/customer returns recorded; financial credit-note policy to confirm | Records integrity |
| D08 | Whether customer pays at delivery, payment methods, credit balances | Record payment method and paid/unpaid status in MVP; no payment processing | Scope |
| D09 | Daily sync topology and connectivity | Dedicated office LAN server/API with durable PostgreSQL; cloud migration later | Multi-device consistency |
| D10 | Reporting, CSV export, QuickBooks handoff | Manual CSV + manual QB status only | Avoid scope creep |
| D11 | Whether stock tracking is required | P0: vehicle loads, sales deductions, returns, count adjustments, EOD reconciliation | Product boundary |
| D12 | Data retention/backup/device loss policy | Daily backend backup and revocation | Operational risk |

## Suggested pilot
- Test with one owner and two drivers over 5–10 business days.
- Prepare synthetic catalogue and 3–5 representative sample invoice formats, then import approved real data privately.
- Run parallel with handwritten books for an initial validation period; reconcile counts/totals at day's end.
- Record average time to issue/print, failure/reprint rate, number of unsynced invoices after closing, arithmetic errors and support requests.
- Graduate from pilot only when all financial records reconcile and data-loss/duplicate-number issues are zero.

## Client sign-off checklist
Business name/address/logo; sample real-world invoice photograph; 2–3 printer choices or existing printer ID; actual tablets/phones OS; customer/product price spreadsheet; numbering preference; required line units; tax rule from accountant; permissions; deployment network preference; 2 pilot users.

## V2 decision precedence
Approved C01–C05 in `08-CLIENT-DECISIONS-V2.md` supersede provisional table rows D01/D08/D09/D11 and corresponding earlier P1 scope. Pilot must reconcile sales **and** payment events **and** stock/returns/physical counts. A minimum 5–10 operating-day parallel run with paper is advised; no claim of readiness until LAN restore, stock reconciliation, and physical print tests pass.
