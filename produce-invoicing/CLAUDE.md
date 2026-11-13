# Claude Code Project Instructions — Produce Invoicing

## Mission
Build a **working offline-first native Android MVP** for Barbados field produce invoicing, guided by the supplied version-4 design. Optimize for 1 owner/master tablet and initially 2 field drivers, with architecture able to add drivers later. Target phone and tablet; test responsive portrait and landscape on representative devices.

## Technology direction
- Kotlin, Jetpack Compose, Material 3 with a visual theme inspired by v4, Navigation Compose, ViewModel/StateFlow, coroutines, Hilt or explicit DI.
- Room/SQLite for durable transactional data; DataStore for non-sensitive preferences. Store tokens/keys via appropriate Android secure storage; avoid logging PII/secrets.
- WorkManager + connectivity monitoring for durable queued synchronization, **not** a substitute for a transactional outbox.
- Printer abstraction with `FakePrinter` for tests, Bluetooth Classic SPP / vendor SDK and Wi-Fi ESC/POS TCP or vendor SDK **only after hardware protocol confirmation**. Android Print Framework/PDF as fallback.
- Backend for pilot multi-device shared records: choose a small, documented authenticated API + PostgreSQL (self-hosted local server at first or suitable hosted environment if devices are geographically separated). Do not require the master tablet to function as an always-on server. Keep domain/sync interfaces independent of backend vendor.
- Use fixed-precision decimal money (minor units, e.g. cents as `Long`, with explicit currency BBD), never binary floating-point money; quantity needs decimal precision and unit.

## Non-negotiable invariants
1. Save data locally transactionally **before** claiming an invoice is issued; printer errors never roll it back.
2. Drafts are editable; issued financial snapshots are immutable. Corrections use voids/credit workflows with audit trails, never silently edit/delete issued invoices.
3. Invoice number scheme is APPROVED V3: customer-facing `PRE-INV-SUF` (e.g. `BGI-100-AB`), unique within a business by immutable `(business_id, driver_identifier, driver_sequence)`, with each driver sequence independent. Issue locally with durable atomic increment, UUID and assigned exclusive driver sequence lease. Do NOT allocate globally non-overlapping number blocks or renumber printed invoices. Server separately allocates monotonically increasing business register reference `PRE-N` (e.g. `BGI-100`) at first accepted registration, without modifying the legal/customer-facing number. See `docs/11-INVOICE-NUMBERING-AND-REGISTER.md`.
4. Every issued invoice stores customer/business/line/price/tax snapshot, actor, device ID, timestamps, and audit event.
5. Every sync upload is idempotent; duplicate requests cannot duplicate invoices. Resolve conflicts explicitly; never last-write-wins on an issued financial record.
6. Driver permissions are enforced in API and repository access, not solely through hidden UI.
7. Printing has prepared/attempted/acknowledged/uncertain states. Transmission success is **not proof that paper printed** unless a device status protocol confirms it.
8. Never claim a Bluetooth/Wi-Fi model works without an integration test on that actual model.
9. No real customer data in test fixtures, screenshots, repos, logs, or demo releases.
10. Keep an automated testable vertical slice at each stage and produce build instructions.

## Working process
- At each task: inspect relevant prototype screens/logic, implement a limited vertical slice, write unit/instrumentation tests, run build/lint/tests (or accurately record blocked prerequisites), document changed files and run instructions.
- Maintain `IMPLEMENTATION_LOG.md` with decisions, evidence, commands/results, remaining issues, and what requires real hardware. Do not mark tasks done based on code being generated alone.
- If something is unconfirmed, implement a mock-backed seam and mark `BLOCKED: hardware/client decision`, not a fictional production integration.
- Avoid coding all tasks in a single uncontrolled pass. Stop after each explicit checkpoint for review.
- Check installed SDK and dependency versions; pin versions in Gradle. Do not invent library version numbers or APIs.
- Make reasonable defaults for reversible UI details. Escalate decisions that affect financial correctness, invoice legal format, network topology, printer protocol, or retention.

## V2 binding client requirements (highest priority after financial invariants)
Client has APPROVED: multiple compatible printer models, 58 mm thermal only, office-LAN sync first with cloud migration later, payment method plus PAID/UNPAID, full operational vehicle stock and returns reconciliation. Read `docs/08-CLIENT-DECISIONS-V2.md`, `docs/09-PAYMENTS-STOCK-RECONCILIATION.md`, `docs/10-LAN-TO-CLOUD-AND-PRINTER-MATRIX.md` BEFORE implementing any task. These supersede old provisional defaults.

Additional invariants:
11. Issue an invoice and corresponding `SALE_DEBIT` stock movements in one Room transaction; upload events idempotently. Never double-decrement on retry.
12. Payment status/method is a separate auditable record; no real payment processing; totals never counted twice.
13. Customer-return inventory is quarantined until authorized disposition; physical return != financial credit/refund.
14. Driver close can be saved offline, but owner APPROVED close requires synced, conflict-free data and documented variance resolution.
15. Local office API is a maintained LAN host; do not repurpose master tablet as server. No unapproved exposed database services, implicit remote access or hardcoded IP.
16. A 58 mm print renderer and multiple adapters must exist; actual model compatibility must be certified with hardware. Do not assert BLE/SPP equivalence.

Task order `00–14` (Task 14 is end-to-end numbering and register verification; task 13 prepares pilot, but final pilot sign-off depends on task 14). All tasks must follow V3 numbering requirements in `docs/11` and `docs/12`.

## V3 client-approved numbering — supersedes ALL prior number-block language
- Driver-facing `PRE-INV-SUF`: `PRE` business abbreviation, `INV` per-driver local increasing integer, `SUF` permanent unique driver code. Driver AB #100 and driver FD #100 are valid concurrently. The driver-facing number is the invoice identifier printed for the customer and MUST remain immutable.
- Independently allocate `PRE-N` on the office server **upon first accepted registration** in a single database transaction. This is an **internal register reference, not a second issued invoice number**. Never backdate, renumber, or reorder central refs after late sync or daily close.
- Keep permanent invoice UUID, driver number, central reference, issued device time, first server received time, registered server time and immutable driver name/id snapshot. Use UTC instants and display in America/Barbados. Client clock is informational, never authority for ordering or identity.
- A driver code/sequence must have one fenced issuing device at a time, with durable local counter and server-side uniqueness. No simultaneous offline reuse of a driver code on multiple devices. Device loss/reprovision has explicit quarantine/recovery; do not start from old sequence or assume lost unsynced records can be reconstructed.
- Idempotency by invoice UUID/event UUID; database uniqueness for `(business_id, driver_number)` and `(business_id, register_sequence)`; register allocation in same transaction as first acceptance of invoice. Voids leave number reservations and audit entries intact.
- Read `docs/11-INVOICE-NUMBERING-AND-REGISTER.md`, `docs/12-NUMBERING-TEST-MATRIX.md`, and `tasks/14-NUMBERING-AND-REGISTER-E2E.md` before touching domain, persistence, identity, sync, reporting or printing.
