# Architecture and Data Design

## Proposed logical layers
Compose UI -> ViewModels -> use cases -> repositories -> Room local tables / printer gateway / Sync API. Backend REST/JSON + PostgreSQL and migration tooling. Background WorkManager processes transactional outbox when connected. Owner role can run same Android app with additional screens.

## Local authoritative while offline
Room transaction includes `Invoice`, `InvoiceLine` snapshots, `AuditEvent`, `OutboxEvent`. The outbox MUST commit in the same database transaction as issuance. Network failure cannot revert issuance. Server acknowledges by UUID and monotonically tracked server revision.

## Core entities
- `business(id, name, address, phone, currency, invoice_footer, tax_config_version)`
- `user(id, role, active, auth_subject, display_name)`; `device(id, assigned_user_id, status, last_sync_at, number_block_id)`
- `customer(id uuid, business_id, name, phone, address, active, server_revision, origin_device_id)`
- `product(id uuid, sku, name, active, server_revision)`; `price(id, product_id, unit, price_minor, effective_at, catalogue_version)`
- `invoice(id uuid, visible_number, business_snapshot, customer_snapshot, actor_id, device_id, catalogue_version, issued_at_device, issued_at_server_nullable, status, subtotal_minor, tax_minor, total_minor, sync_state)`
- `invoice_line(id uuid, invoice_id, product_id_nullable, name_snapshot, unit, quantity_decimal_string, unit_price_minor, line_total_minor, tax_snapshot)`
- `number_block(id, device_id, prefix, from_seq, to_seq, next_seq, server_allocation_id)`
- `print_attempt(id uuid, invoice_id, printer_id, started_at, transport, outcome, diagnostic, copy_number)`
- `outbox(id uuid, type, entity_id, payload_json, attempts, next_attempt_at, acked_at)`
- `audit_event(id uuid, actor_id, device_id, action, entity_id, timestamp, details_json)`

## Numbering
V3: Driver sequence is scoped to immutable driver code; customer-facing `PRE-INV-SUF` is assigned offline. Server atomically assigns separate internal `PRE-N` on first accepted registration. See `docs/11`. Never renumber an issued invoice.

## Money and quantity
Represent prices/totals in integer BBD cents (`Long`) and quantities in `BigDecimal` decimal text with defined scale (e.g. 3 decimal places); use decimal math for line extensions and explicitly defined rounding HALF_UP to 2 decimals **subject to client approval**. Validate quantity precision, maximum amount and units. Test 0.1+0.2 and fractional weights.

## Invoice lifecycle
DRAFT -> ISSUED -> VOIDED (privileged event/reversal). A print status is orthogonal: NOT_PRINTED, QUEUED, ATTEMPTING, TRANSPORT_SENT, CONFIRMED, UNCERTAIN, FAILED. Sync state is orthogonal: PENDING, SYNCING, SYNCED, CONFLICT, FAILED_RETRYABLE.

## API sketch
`POST /auth/session`, `GET /bootstrap?since=revision`, `POST /devices/register`, `POST /driver-sequences/allocate`, `POST /sync/batch` (idempotency key required), `GET /sync/changes?since=cursor`, `GET /owner/daily-summary?date=...`, `POST /invoices/{id}/void` with privileged authorization. Document request/response schema and concurrency/errors; enforce business tenant scope at every endpoint.

## Deployment choice
For a 2-driver pilot, an authenticated small backend + managed PostgreSQL is recommended if drivers sync from the field. If strictly same-location Wi-Fi and no cloud, a LAN server can be used, but **not an always-running master tablet**; remote sync requires network visibility/VPN and explicit management. Backend deployment to AWS later is an implementation detail; keep API portable.

## V2 schema and transaction additions
Add `payment_event`, `stock_location`, `vehicle`, `shift`, `stock_allocation`, `stock_conversion`, `stock_movement`, `stock_count`, `stock_variance_resolution`, `return_intake`, `printer_profile`. Each carries stable UUID, business scope, actor/device, timestamps, server revision/ETag where applicable. Constraints: unique `(tenant, idempotency_uuid)`, `(tenant, invoice_uuid, invoice_line_uuid, movement_kind=SALE_DEBIT)` and non-overlapping shift/vehicle assignments. Never use floating point for stock quantities. Convert selling quantities to stock base units using versioned product mapping and exact decimal arithmetic. In invoice issue DB transaction persist issued snapshot + outbox + sale debits. Payment recording is a separate auditable operation. Local server is authoritative for master data and aggregated shifts after sync, never the owner's tablet.

## V3 storage/indexes
- Local UNIQUE `(business_id, driver_code_snapshot, driver_sequence)` and UUID; server UNIQUE `(business_id, customer_invoice_number)`, `(business_id, register_sequence)` (nullable), UUID and event UUID; use foreign keys, NOT NULL validation, and server-owned atomic counters.
- Central register reference remains nullable until first accepted sync; UI and exports must label it **Internal register reference**. No central register number generated on the client.
- Driver code assignment unique for the business and never reassigned for a new person; one active issuing device lease/epoch per code. Driver-issued sequence persists across daily shifts and device transfers.
