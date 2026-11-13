# Approved client decisions — 13 November 2026

These supersede any conflicting provisional defaults in earlier docs.

| ID | Decision | Meaning for MVP | Unresolved gate |
|---|---|---|---|
| C01 | Multiple printer models | Pluggable thermal printer drivers; tested per profile/model/transport | Manufacturers, firmware, Bluetooth protocol and network capabilities |
| C02 | 58 mm thermal paper | Width-specific layouts; character columns calibrated per model | Printable width, code page, cutter/cash drawer capabilities |
| C03 | Local first; cloud later | Office-hosted API/database on LAN, phones fully offline between visits, cloud-agnostic deployment | Office server machine, LAN topology, backup/UPS, remote access policy |
| C04 | Method + paid/unpaid | Method and status captured with invoice/payment event; reports split by status and method | Accepted method list; whether partial/mixed payments eventually needed |
| C05 | Stock and returns reconciliation | Per-driver/vehicle loading, sale consumption, returns, count and variance, owner close approval | Units/conversions, spoiled goods, return disposition, negative stock rules |

## Non-negotiable operational decisions
- Local-first does not mean **master tablet as a server**. Use an independently maintained office LAN host. No local Wi-Fi/office access means no sync, while issuing and printing still work.
- No cloud dependency in pilot invoice path. Provision users, exclusive driver issuing leases and stock allocations before taking devices offline; administrator can review only records already synced.
- Payment method and status are recorded facts and may be corrected through auditable payment events; they do not initiate or verify external transactions. Never treat `CARD` as embedded card processing.
- Returns have two independent dimensions: (1) physical stock disposition, (2) commercial remedy (credit/refund/replacement). Recording a physical return alone must not alter an issued invoice.
- Physical stock counts and unsynced records must be visible at closing. An owner cannot approve final close against incomplete uploads without explicit exception handling.

## Frozen P0 vs later
P0: 58 mm print on *at least two real compatible models* if two are available, with a documented compatibility matrix; LAN sync and eventual consistency; payment method and paid/unpaid; stock movements and returns; owner/driver reconciliation.
P1: partial/mixed payments and outstanding balance ledger; credit notes/refunds; cloud hosting migration execution; stock valuation; advanced multi-depot transfers; QuickBooks API.

## Open client gates
1. List of physical printer models and whether two distinct models can be supplied for testing.
2. Exact payment options and whether 'unpaid' invoices may have a method of 'Not yet selected'.
3. Define invoice unit/stock unit mapping: cases, bags, pounds/kg, pieces, decimal weights; explicit per-product conversions.
4. Are customer returns sellable, damaged, or supplier-bound? Who authorizes quantity adjustments?
5. Whether stock may go below zero while offline and whether sales can exceed vehicle allocation.
6. Office LAN host, OS, static DHCP/IP, UPS, backup owner and recovery target; network separation and VPN if needed.
7. Barbados tax/invoice wording, return/credit accounting, rounding and money controls approved by accountant.
