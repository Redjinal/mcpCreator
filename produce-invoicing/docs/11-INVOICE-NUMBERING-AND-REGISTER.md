# V3 — Approved Invoice Numbering and Central Register (binding)

## Client-approved behavior
- **Customer/driver invoice number:** `PRE-INV-SUF`. `PRE` is a business abbreviation (e.g., `BGI`); `INV` is the **driver's own** running invoice sequence (e.g., `100`); `SUF` is the assigned immutable driver identifier (`AB` or `FD`). Two drivers can both issue `100` concurrently: `BGI-100-AB` and `BGI-100-FD`.
- **Office register reference:** `PRE-N`, e.g. `BGI-100`, `BGI-101`, assigned centrally when the server first accepts each new invoice. It is an **internal tracking reference** for owner reconciliation; not an additional invoice issued to the customer. It is not initially available offline. Customer-facing number never changes after registration.
- **Permanent identity:** UUID generated at issue, never reused, used to link invoice lines, payment events, stock movements, print attempts, voids, register mapping and audit trail.
- Driver's full display name is captured separately as an immutable issue-time snapshot. It is not part of any number.

## Format & scoping
- `businessCode` normalized uppercase [A-Z0-9] (e.g., 2–8 chars, configurable after client review); `driverCode` normalized uppercase [A-Z0-9] (e.g., 1–8 chars), unique and *never reused* within the business; positive `Long` sequence with bounded maximum and explicit overflow handling. Avoid hyphens in codes to keep parsing unambiguous.
- Tenant/business UUID, rather than readable abbreviation alone, determines database uniqueness. Freeze abbreviation for issued invoices; a business rename must not rewrite earlier numbers. Existing driver-issued numbers survive code/name edits; don't recycle suffixes.
- Composite unique `(business_id, driver_code, driver_sequence)` and unique `(business_id, customer_invoice_number)`; UUID global unique. For central references unique `(business_id, register_sequence)` and `(business_id, register_reference)`; central counter scoped by business. A 2nd business may independently have `100`.
- Initial sequence can be configured by owner when a driver is first provisioned; **do not assume it must start at 100**. Next sequence survives shifts, app restarts, and device replacement. No arbitrary reset at midnight.

## Local issuance transaction
1. Authenticate authorized driver; confirm local *single issuing device* lease and active business assignment. A lease includes driver code, device ID and monotonically increasing epoch. A stale/revoked lease cannot resume issuance once revocation is learned; define an offline credential-expiry policy that does not promise instant remote revocation.
2. Within ONE Room transaction, atomically read/increment the driver's durable sequence, assign invoice UUID and `PRE-INV-SUF`, freeze customer/line/price/tax/driver snapshots, write linked `SALE_DEBIT` stock ledger events, append audit and outbox. Rollback everything on failure.
3. Render receipt with the original customer number, even without network. Print success does not create an invoice or determine its identity.
4. Parallel taps, app restarts and process death cannot reuse a committed sequence. Gaps after a void are allowed but not reused; there must be an audit reason for void.

## Server first-registration transaction
1. Validate tenant, actor, device lease epoch, immutable original number, event signature/authorization, invoice UUID, totals, associated stock movement identities and request idempotency.
2. On repeated identical UUID/event, return existing result and exact assigned central reference. Never allocate again. If different UUID collides on `(business_id, customer_invoice_number)`, return a structured `NUMBER_COLLISION` conflict; quarantine, do not renumber automatically.
3. Atomically lock/update a per-business register counter (`SELECT ... FOR UPDATE` or equivalent), insert invoice and mapping, allocate next register sequence and commit. Concurrent first arrivals receive distinct register numbers. Server clock provides first_received_at and registered_at instants. Do not rely on `MAX()+1` without proper locking.
4. ACK contains UUID, immutable customer invoice number, optional central `PRE-N`, server times, accepted event IDs and server revision. Store ACK idempotently in Room. Outbox ACKing must not reissue stock debits or payment events.
5. Register sequence ordering is by **first successful registration**, not by client issue time, and is never regenerated at daily close. Reconciliation reports may sort by device issue time separately; detect clock skew and flag, never rewrite central references.

## Device handoff / edge cases
- One driver code must not have two independently issuing offline devices. Enforce exclusive assignment and a fencing epoch. Replacing a device requires server reconciliation and reliable counter continuity; if previous device has missing unsynced invoices or cannot be accounted for, stop and require a documented exception / new permanent suffix policy. Do not guess the last number.
- Stale offline devices may still have an old cached lease; server rejects old epoch on return and surfaces a conflict needing review, NOT silent deletion. Determine business-safe provisioning policy before real rollout.
- A failed sync retry cannot allocate new internal reference. Late-arriving records retain original shift date and receive later server registration. Post-close late invoices require an explicit exception workflow and approval.
- Register numbering starts at configurable per-business integer; `BGI-100` is **only an example**. The business register may have gaps on voided historical entries but never reuses a committed reference. Document accounting/legal numbering review before production.

## Auditability and display
- Invoice detail: `Invoice: BGI-100-AB`; `Driver: <name>`; `Internal register reference: BGI-101` only when synced. Owner search on both identifiers and UUID. 58 mm thermal invoice shows `PRE-INV-SUF`, not just `PRE-N`.
- Persist UTC client-issued time (mark as device reported), server-first-received time, server-registered time, local timezone `America/Barbados` display; capture device clock offset/skew diagnostics. Timestamp alone is NEVER the unique key.
- Exports show invoice UUID, primary printed ID, register reference, driver snapshot, issue UTC, first-received UTC, registered UTC, shift ID, statuses and event counts. Never join payments or movements by register sequence alone.

## Non-goals and policy boundaries
- No retrospective reassignment of central numbers based on end-of-day sort. If a **chronologically ordered official** number series is required by an accountant, this offline workflow and assumptions need business/accounting review BEFORE production.
- Regulatory invoice formats, tax/VAT identifiers and record retention require client accountant/legal confirmation. Avoid asserting compliance.
