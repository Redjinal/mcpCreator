# V3 Amendment — Dual invoice identifiers (13 November 2026)

Client has **approved** per-driver numbering `PRE-INV-SUF` and server-side business register reference `PRE-N`.

## Changed from v2
- Removed v2 approach of globally non-overlapping invoice number blocks. Driver suffixes make independently maintained local `100` sequences unique within a business.
- Primary customer invoice: `BGI-100-AB`, `BGI-100-FD`; remains immutable through offline issue, sync, printing and reprint.
- Central office register: `BGI-100`, `BGI-101`, assigned atomically on first successful server registration (not nightly re-numbering). This is a **tracking reference**, not a second official printed invoice.
- Driver-specific fenced device issuance lease; durable monotonically advancing per-driver counters. Driver suffixes are not recycled.
- Required first-received/registered/server timestamps, reporting by original issue date, clock-skew and late-arrival exception handling.
- Updated domain, persistence, role/provisioning, sync, printing, owner reporting, close and pilot tasks; added `docs/11`, `docs/12`, `tasks/14`.
- V2 decisions remain: 58 mm multiple printer models, office LAN then cloud, payment method and PAID/UNPAID, stock/returns reconciliation.

## Priority of docs
`CLAUDE.md` and V3 `docs/11` supersede all earlier references to exclusive global number blocks in older docs or archived prototype screens.
