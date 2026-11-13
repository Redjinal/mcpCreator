# Offline Sync, Security and Recovery

## Sync algorithm
- On first authenticated provisioning, obtain user/device authorization, catalog version, customers, and an exclusive driver-code issuance lease.
- On every local mutation, write relevant outbox event in the SAME Room transaction.
- WorkManager retries on connectivity (exponential backoff); expose manual Sync Now and detailed status.
- Server accepts each event ID idempotently; unique constraints on `(tenant_id, invoice_uuid)` and `(tenant_id, visible_number)`.
- Upload append-only issued invoice events and void events; receive authoritative ACKs and updated master data since cursor.
- Catalogue conflicts: owner server wins for future drafts; an already-issued invoice retains full historical snapshot. Offline-created customer merges must be reviewed by stable ID / likely duplicates, not silently overwritten.
- Reprovision / lost phone: remote revoke access, restore server-confirmed records. Clearly identify unrecovered local-only invoices on lost devices; no claim of guaranteed restoration of data never synced.

## Security
TLS in transit, scoped auth tokens, refresh rotation as appropriate, Android Keystore-protected credentials, room/local DB encryption if justified by threat model and maintained library availability, server-side RBAC with explicit tenant isolation, no secrets in APK/repo, minimal logs, backup encryption, revocable device access, screen timeout policy. Offline capability must be bounded (cached grants/expiry) without silently giving unrestricted access.

## Privacy & compliance
Barbados client data, customer contact information, storage location, retention and access should be reviewed against applicable Barbados legal obligations and customer contract; do not assert legal compliance based on this prototype.

## Audit & recovery
Append-only events for issuance, voids, failed/successful syncs, price changes and reprints. Backup/restore drill; power-cut data integrity; migration rehearsal. Use server clock for receipt timestamp on sync while retaining client timezone/creation timestamp; prevent clock manipulation from affecting identity.

## Threat cases to test
Replay same sync event 10x; device clock skew; stale catalogue; two devices sharing same login; token revoked while offline; 1000 queued invoices; database migration failure; process killed during issuance; print timeout after bytes sent; incomplete/duplicate customer creation.

## V2 local-only networking and new events
Pilot API reachable on approved office LAN (or administered VPN); no internet/cloud required and no remote sync assumed. Shift and stock allocations, printer config and driver-code issuing leases provisioned before field departure. Payment events and stock movements are journaled in the same transactional outbox as invoicing, with UUID idempotency on upload and durable server uniqueness constraints. Reconcile conflicts rather than discard or overwrite movements. Full close approval requires all related shift events to be synced and resolved. Maintain encrypted backup/restore drill and secure endpoint certificates. Cloud move must preserve keys, UUIDs, tenant scopes, event cursors and audit history.

## V3 registration ordering and offline numbering
The client creates the immutable customer-facing `PRE-INV-SUF` and UUID offline. Server verifies driver code lease/authorization and unique number before insertion, allocates `PRE-N` register reference atomically on **first valid registration**, and returns the same register reference for all identical retries. A conflict with a *different* UUID for the same driver-facing number is quarantined for manual resolution, never auto-renumbered. Register order reflects accepted registration, not device-reported issue chronology. Save issued_at_device_utc, first_received_at_server_utc, registered_at_server_utc. Detect clock skew and keep late-arrival reports visible. Never grant multiple devices the same independent driver sequence at the same time; device loss needs a deliberate transfer procedure.
