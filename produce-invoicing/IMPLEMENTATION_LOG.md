# Implementation Log — Produce Invoicing MVP

Binding instructions: `CLAUDE.md` (V3). Each checkpoint records decisions, exact commands, results and blockers.
Nothing is marked done on the basis of generated code alone.

---

## Checkpoint — Task 02: Durable offline data (Room) (2026-11-13)

**Status: ACCEPTANCE GATE MET on JVM/Robolectric (real SQLite engine). On-device instrumentation BLOCKED: no phone/emulator. Stopped for review — Task 03 not started.**

### What was built (`android/core/data`)

| File | Contents |
|---|---|
| `db/EntitiesV1.kt` | Schema v1: `device_identity`, `business`, `driver_lease`, `customer`, `product`, `product_unit_price`, `draft`, `draft_line`, `invoice`, `invoice_line`, `audit_event`, `outbox_event`. |
| `db/EntitiesV2.kt` | Schema v2 (V2 scope): `location`, `shift`, `stock_conversion`, `stock_movement`, `payment_event`, `return_intake`, `stock_count`, `printer_profile`, `print_attempt`; plus `business.negative_stock_policy`. |
| `db/Schema.kt`, `db/MigrationSql.kt` | 26 integrity triggers; `MIGRATION_1_2` (25 statements **generated from the exported `1.json`/`2.json`**, so it matches Room's expected schema exactly). |
| `db/Daos.kt`, `db/Mappers.kt` | DAOs; row↔domain mapping. Issued invoices are read back through `IssuedInvoice.restore`, which recomputes totals (new in the domain). |
| `InvoicingRepository.kt` | Drafts (atomic save, refuses to resurrect an issued draft), **the issue transaction**, server-ACK registration, void, payment events, outbox, storage summary. |
| `DemoSeeder.kt` | SYNTHETIC demo data (business `DEMO`, 3 customers, 4 products, conversions, van, OPEN shift, opening load, driver `AB` lease from 100 bound to this device). Dev flavor only. |
| `schemas/…/1.json`, `2.json` | Exported Room schemas (used by `MigrationTestHelper`). |

**The issue transaction** (`InvoicingRepository.issue`), one Room transaction:
1. If an invoice already exists for this draft, return it (`AlreadyIssued`) — double-tap safe (N18).
2. Load draft, business, this driver's lease, catalogue, customer, the driver's OPEN shift on this device, conversions.
3. Pure domain step: validate, allocate the next per-driver number from the lease, freeze snapshots.
4. Convert lines to base units and check them against the van's stock ledger with the business's negative-stock policy.
5. Write, in order: invoice → lines → **compare-and-set** lease advance (`WHERE next_sequence = :expected AND epoch = :epoch AND revoked = 0`; anything but 1 row rolls back) → SALE_DEBIT movements → audit → outbox (`INVOICE_ISSUED` + one `STOCK_MOVEMENT` per debit) → delete draft.
Any rejection or exception writes nothing.

**Database-level guarantees (SQLite triggers + unique indexes)** — hold even against raw SQL:
- Issued invoice snapshot columns can't change; invoices and lines can't be deleted.
- Register reference: set once, all four fields together, prefix must equal the business code.
- Void is final.
- Unique `(business, driver_code, driver_sequence)`, `(business, customer_invoice_number)`, `(business, register_sequence)`, `source_draft_id`.
- Driver lease: sequence and epoch never decrease; identity fixed; never deleted.
- `stock_movement`, `payment_event`, `audit_event`, `return_intake`, `stock_count`: append-only. One SALE_DEBIT per invoice line; one reversal per payment event.
- One active shift per vehicle and per driver.
- Foreign keys enforced.
- Triggers are re-asserted (`IF NOT EXISTS`) on every open, including after migration.

**App wiring:** `ProduceInvoicingApplication` holds the database (explicit DI). Dev builds seed synthetic data (`BuildConfig.SEED_SYNTHETIC_DATA`, false for pilot). The status screen now shows real counts: `Ready · schema v2 · 0 issued · 0 drafts · 0 waiting to sync`. No destructive migration fallback is configured — a missing migration crashes loudly instead of wiping unsynced records.

### Decisions (flag for review)

| Decision | Why | Reversible? |
|---|---|---|
| Issuing requires the driver's **OPEN shift** on this device | Invariant 11: the SALE_DEBIT must be written in the same transaction, and needs a vehicle/shift. | Yes |
| `negative_stock_policy` stored per business; migration default and demo seed = `BLOCK` | Client gate 5 is open; BLOCK is the conservative default (it can stop a sale when van stock is short). | Yes, one column |
| An injected failure leaves the number **unused**; the next issue reuses it | It was never committed or printed. Only committed numbers are never reused (docs/11). | — |
| Instants stored as epoch milliseconds; the repository truncates its clock to ms | Exact round-trip of issued invoices. | — |
| Outbox rows carry type + aggregate id (no payload copy) | Issued records are immutable, so the uploader can read them at send time (Task 05). | Yes |

### Commands and results

```
cd shared  && ./gradlew test
cd android && ./gradlew assembleDevDebug assemblePilotDebug testDevDebugUnitTest testPilotDebugUnitTest \
                         :core:data:testDebugUnitTest lintDevDebug lintPilotDebug assembleDevDebugAndroidTest
cd server  && ./gradlew test installDist
```

| Suite | Tests | Result |
|---|---|---|
| `IssueTransactionTest`: full write set; **rollback at each of the 7 write steps** (no rows, lease still 100, draft kept); **N18** 8 concurrent issues of one draft → 1 Issued + 7 AlreadyIssued; **N07/N08** draft and invoice survive close/reopen and the sequence continues next day (101, 102); rejections write nothing (no customer, insufficient stock, revoked lease, no open shift, missing conversion); payment + stock events survive restart and 10× replay adds nothing; outbox ACK idempotent | 9 | passed |
| `SchemaConstraintsTest`: immutability, register-once/prefix, void-final, uniqueness (incl. N06 locally), forged totals detected on read, lease monotonic/fixed/undeletable, append-only ledgers, duplicate SALE_DEBIT refused (S2), one active shift per vehicle/driver (S4), FKs | 8 | passed |
| `RegistrationVoidTest`: register once / identical replay no-op / conflict refused / unknown invoice; survives restart with all 3 timestamps; void owner-only, audited, queued, number kept, not recycled (N13); N09 driver rename | 3 | passed |
| `MigrationTest`: real v1 DB from `1.json` with a v1 invoice, lease and outbox → `MIGRATION_1_2` → validated against `2.json`; data intact, policy defaults to BLOCK, triggers present; production builder reads it and immutability holds | 1 | passed |
| App `MainActivityStorageTest` (real activity opens Room; synthetic seed only in dev) + earlier app tests, ×2 flavors | 10 + 10 | passed |
| Shared domain (incl. new `restore` test) | 174 | passed |
| Server (incl. Testcontainers PostgreSQL) | 8 | passed |
| Lint dev / pilot | — | 0 errors; warnings unchanged (`OldTargetApi`, dev-only `InsecureBaseConfiguration`) |

**Mutation checks (to prove the tests can fail).** I ran each one temporarily and then reverted it:
- Disabling the trigger callback made 6 `SchemaConstraintsTest` tests fail.
- Removing the transaction around `issue` made the rollback and N18 tests fail.

Debug APK SHA-256 (rebuilds are not bit-for-bit reproducible; re-hash anything you distribute):
dev `81ee9db055cfa99132a465144c37353d9c3109a9ba695bf230c37f5de517cb84`, pilot `b617176d990d5b8ea36d24b3dc0e5512620341d3dab38ed9832537e486e602ce`.

UI evidence: `docs/evidence/task02/` (JVM renders of the status screen with the storage line).

### Environment notes
- KSP 2.3.13 works with AGP 9's built-in Kotlin (no extra flags).
- After a container restore, Docker would not start: `/var/run/docker.pid` and `containerd.pid` named PIDs that had been **recycled by unrelated processes** (one was a Gradle daemon thread). I removed a pid file only after checking that `/proc/<pid>/cmdline` was not dockerd/containerd. No process was killed.

### Blockers / not covered
- **BLOCKED: hardware** — the same scenarios as on-device instrumentation (real app kill during issue, reboot). Robolectric uses a real SQLite engine, but not the device's storage stack or process death. True process kill mid-transaction relies on SQLite's journal atomicity; it is not simulated here beyond injected exceptions.
- Lease provisioning, revocation sync and device handoff — Task 04. The server register (N02–N06, N16) — Task 05. Print attempts and printer profiles have tables but no flow — Task 06. Returns, counts and shift transitions — Tasks 11/12.

### Recommended next task
**Task 03 — Responsive field UI** on top of this repository (drafts, issue, invoice list/detail, customers/products, owner shell). Awaiting approval.

---

## Checkpoint — Task 01: Domain models and arithmetic (2026-11-13)

**Status: ACCEPTANCE GATE MET (pure domain, unit-tested). Stopped for review — Task 02 not started.**

All code is in `shared/domain` (`bb.produce.invoicing.domain.*`): plain Kotlin/JVM with no Android, database or network dependencies. Both the app and the server compile against it. Nothing in this task is persisted yet — durability, the atomic Room transaction and the server register are Tasks 02/04/05.

### What was built

| Package | Contents | Key rules enforced |
|---|---|---|
| `common` | UUID value classes (`InvoiceId`, `LineId`, `EventId`, …), `Actor`/`Role`, `Outcome`, `BarbadosTime` | UTC instants; display in `America/Barbados` (`13 Nov 2026, 09:00 AST`). |
| `money` | `Money` (Long cents + `Currency.BBD`), `Quantity` (BigDecimal, scale 3), `UnitPrice`, `UnitCode`, `LineMath`, `RoundingPolicy`, `TaxSnapshot` | No floating point. Overflow-checked arithmetic. Quantity > 0, ≤ 3 decimals, ≤ 99,999. Unit price 0–999,999.00, ≤ 2 decimals. Line total = qty × price, **HALF_UP to the cent per line, then summed** — same as v4 `cents()`. Tax: `NotApplied` only. |
| `identity` | `BusinessCode`, `DriverCode`, `DriverSequence`, `CustomerInvoiceNumber` (`PRE-INV-SUF`), `RegisterSequence`, `RegisterReference` (`PRE-N`), `DriverSnapshot`, `DriverSequenceLease` | Codes `[A-Z0-9]`, no hyphens. Sequence 1..999,999,999, overflow is an explicit error. Strict canonical parsing (no leading zeros, so `BGI-0100-AB` cannot alias `BGI-100-AB`). `PRE-N` and `PRE-INV-SUF` cannot be confused. Lease is fenced by device + driver code + epoch; revoked/expired/exhausted leases cannot issue. **No number blocks.** |
| `catalogue` | `Business`, `Customer`, `Product`, `ProductUnitPrice`, `CatalogueVersion`, snapshots, `StockConversion`, `ConversionTable`, `BaseQuantity` | Products list their permitted units; `price == null` = price entered on invoice. Conversions are explicit, versioned and exact (≤ 6-decimal factor); a missing conversion is an error, never 1:1. |
| `invoice` | `InvoiceDraft`/`DraftLine` (editable), `DraftValidator`, `InvoiceIssuer`, `IssuedInvoice`/`IssuedLine`, `ServerRegistration`, `VoidRecord`, `InvoiceStatus`, `SyncState` | Draft edits return new values. Zero price needs confirmation; editing the price clears it. Changing unit re-applies the catalogue price and does **not** convert quantity. Catalogue price override needs authorisation (D06). **Issue is one pure step** that allocates the next per-driver sequence from the lease and freezes business/customer/driver/line/price/tax snapshots, actor, device, lease epoch, catalogue version and rounding policy id; it returns the invoice **and** the advanced lease so Task 02 can persist both in one transaction. `IssuedInvoice` has no public constructor or `copy`; lines are an unmodifiable list. Registration attaches `PRE-N` once (identical replay is a no-op; a different reference is `CONFLICTING_REGISTER_REFERENCE`); the customer number never changes. Void: owner only, reason required, once; number and values kept. Three timestamps plus a device-clock-ahead flag. |
| `payment` | `PaymentStatus`, `PaymentMethod`, `PaymentEvent`, `PaymentLedger`, `PaymentSummary`, `ExternalReference` | UNPAID by default. PAID needs a non-`NONE`, enabled method and amount == outstanding (no partial/over/mixed). Corrections are reversal events (owner only, reason required). Recording is idempotent by event id. Paid totals are a breakdown, never added to sales. External references with a Luhn-valid 13–19 digit run (likely a card number) are rejected. |
| `stock` | `StockMovementKind`, `StockBucket`, `ReturnDisposition`, `StockMovement`, `StockLedger`, `SaleDebits`, `StockPermissions`, `NegativeStockPolicy`, `ShiftStatus`, `ShiftLifecycle`, `CloseReadiness` | Append-only, idempotent ledger; one SALE_DEBIT per invoice line (`SaleReference(invoiceId, lineId)`). Returns go to QUARANTINE. Drivers cannot load, top up, release quarantine or adjust counts. Negative stock policy must be passed explicitly. Shift approval is refused while unsynced events, conflicts or unexplained variances remain. |
| `printing` | `PrinterProfile` (58 mm only), `Certification`, `PrinterTransport`, `PrintAttempt`, `PrintStatus`, `PrintEvidence` | `TRANSPORT_SENT` ≠ printed. CONFIRMED only by device status (if the profile supports it) or operator. Interrupted send → UNCERTAIN; the next print is a COPY that needs a reason. Profiles are `Unverified` until certified with evidence. |
| `audit` | `AuditEvent`, `AuditAction`, `OutboxEvent`, `OutboxEventType` | Audit details refuse PII/secret keys. Outbox retries count up; ACK is idempotent. |

### Decisions needing client/accountant confirmation (all reversible, all flagged in code)

| Item | Provisional choice | Where |
|---|---|---|
| Rounding | HALF_UP per line to the cent, then sum (matches v4). `RoundingPolicy.APPROVED_BY_ACCOUNTANT = false`; the policy id is frozen on each invoice. | `money/LineMath.kt` |
| Tax/VAT | Not applied; no rate invented. | `TaxSnapshot.NotApplied` |
| Limits | Qty ≤ 99,999 (3 dp); unit price ≤ 999,999.00; invoice total ≤ BBD 9,999,999.99; driver sequence ≤ 999,999,999. | `Quantity`, `UnitPrice`, `DraftValidator`, `DriverSequence` |
| Payment reversal | Owner only. | `PaymentLedger.reversePaid` |
| "Unpaid with expected method" | Not modelled (docs/08 gate 2 open). | — |
| Quarantine release | Added `QUARANTINE_RELEASE` movement kind (not in the docs/09 list, which has no way to release a resellable return). | `stock/Stock.kt` |
| Negative stock | No default; caller must choose `BLOCK` or `ALLOW_AND_FLAG` (gate 5). | `NegativeStockPolicy` |
| Custom (non-catalogue) lines | Allowed, priced manually, **not stock-tracked**. | `SaleDebits` |
| Who may void | Owner only, reason required (D07). | `IssuedInvoice.void` |

### Commands and results

```
cd shared  && ./gradlew test
cd android && ./gradlew assembleDevDebug assemblePilotDebug testDevDebugUnitTest testPilotDebugUnitTest lintPilotDebug
cd server  && ./gradlew test installDist
```

| Suite | Tests | Result |
|---|---|---|
| `MoneyQuantityTest` (0.1+0.2, formatting, overflow, parsing, invalid quantities/prices/units) | 45 | passed |
| `LineMathTest` (HALF_UP cases incl. half-cent; **20,000 random lines match the v4 `cents()` formula**; policy flags) | 14 | passed |
| `InvoiceIdentityTest` (BGI-100-AB vs BGI-100-FD, PRE-N separation, invalid codes, zero/negative/overflow, 50k unique UUIDs, lease fencing) | 41 | passed |
| `DraftAndIssueTest` (quantity changes, invalid units, zero-price confirmation, price override, immutability, A2 2×25 offline, A5, N01, N09, N11, N13, registration idempotency, void) | 24 | passed |
| `PaymentsTest` (P1, P2, P3, method rules, 10× replay, void reporting, card-number rejection, long-reference regression) | 17 | passed |
| `StockAndShiftTest` (S1 20−5−1 = 14 saleable / 2 quarantined, S2 10× replay, conversions 2 case → 24 each and 1.5 kg → 3.30693 lb, permissions, negative policy, reversal, S5 approval gate) | 12 | passed |
| `PrintingAuditTest` (58 mm only, sent ≠ printed, uncertain → COPY with reason, audit PII guard, outbox ACK idempotency, Barbados time) | 7 | passed |
| `ServerEndpointTest` (Task 00) | 13 | passed |
| **shared total** | **173** | **all passed** |
| Android build + unit tests (both flavors) + lint pilot | — | BUILD SUCCESSFUL; lint 0 errors, 1 warning (`OldTargetApi`, intentional) |
| Server tests (incl. Testcontainers PostgreSQL) + installDist | 8 | passed |

Found and fixed during this task:
- **Card-number check too aggressive.** The first version tested every 13–19 digit window inside a longer digit run, so about 1 in 10 windows passes Luhn by chance and long bank references would be falsely rejected. Now only whole runs are tested, with a regression case for a 23-digit reference.
- A test-data bug (`1,000.00` split by the CSV source) — test fixed, code unchanged.
- **Environment:** this session's container came back with stale `docker.pid` / `containerd.pid` files, so the daemon would not start and `MigrationIT` failed with "Could not find a valid Docker environment". I removed the two stale pid files (their PIDs no longer existed), started `dockerd`, and the server suite then passed. No code change.

`grep` for number-block remnants (`number block`, `nextSeq`, `padStart`, `INV-0`) in active source: only per-driver `nextSequence` and comments stating there are no blocks.

### Not covered by Task 01 (by design)
Persistence and the atomic Room transaction (02), UI (03), provisioning and server-issued leases (04), server registration/concurrency, i.e. N02–N06 and N16 (05), receipt rendering (06), real printers (07, BLOCKED: hardware). N07, N08 and N18 need durable storage and are verified in Task 02.

### Recommended next task
**Task 02 — Room offline persistence**: entities/DAOs/migrations for these models, and ONE transaction that persists the issued invoice + advanced lease + SALE_DEBIT movements + audit + outbox. Awaiting approval.

---

## Checkpoint — Task 00: Audit and scaffold (2026-11-13)

**Status: ACCEPTANCE GATE MET (build/scaffold). Stopped for review — Task 01 not started.**

### Where this lives
The pack expects its own repository. The only repository available to this session is
`Redjinal/mcpCreator` (an unrelated Envato MCP relay), so the MVP lives self-contained under
`produce-invoicing/` on branch `claude/wizardly-clarke-3cuaom`. Nothing outside that folder was
changed. It can be moved to a new repository unchanged (`git subtree split --prefix=produce-invoicing`).

### Layout and architectural decisions

| Path | What | Decision / rationale |
|---|---|---|
| `gradle/libs.versions.toml` | One version catalog shared by all three Gradle builds | Every version checked against live Maven metadata on 2026-11-13 (no invented versions). |
| `shared/` (`:domain`) | Pure Kotlin/JVM, no Android/server deps | Consumed by **both** Android and server through Gradle composite builds (`includeBuild("../shared")`) so money/number-format rules (Task 01) have one implementation. Contains only `ApiVersion` and `ServerEndpoint` today. |
| `android/` | `:app` (Compose), `:core:data` (Android lib, Room in Task 02), `:core:printing` (JVM, Task 06), `:core:sync` (JVM, Task 05) | Boundaries keep printing/sync pure-Kotlin and testable on the JVM; Android specifics stay in `:app`/`:core:data`. Core modules are **empty boundaries** with a README each. |
| `android/app` flavors | `dev` (`.dev` app id, cleartext allowed) and `pilot` (HTTPS only) | API base URL comes from Gradle property `pi.apiBaseUrl.<flavor>`; empty ⇒ UI says "Not configured". No IP or hostname in source (invariant 15). |
| `server/` | Ktor 3 + HikariCP + Flyway + PostgreSQL | Small, portable JVM service; same image runs on an office host now and a cloud host later (docs/10). Only `GET /api/v1/health` exists. |
| `deploy/` | `docker-compose.yml`, `.env.example`, `scripts/backup.sh`, `scripts/restore.sh` | DB has no published port and sits on an `internal` network; API binds to 127.0.0.1 unless `PI_API_BIND` is set. Images pinned by digest. Runtime-only API image (built from `installDist`). |
| `api/openapi/v1.yaml` | Versioned API schema | Documents only implemented endpoints; planned endpoints listed in the description. Old "number block allocation" endpoint renamed to a driver-sequence **lease** per V3. |
| `docs/00-V4-PROTOTYPE-INVENTORY.md` | v4 screens, interactions, data objects, simulated behaviours, tokens | Prototype read as data; not executed. |
| `docs/13-TRACEABILITY-MATRIX.md` | Prototype screen → native screen → data layer → task → status | Marks what is deliberately **replaced** (manual number override, offline-blocks-issue, fake print/sync). |
| `docs/runbooks/BACKUP-RESTORE.md` | Initial office-server run/backup/restore runbook | |

Other decisions:
- **AGP 9.5.1 / Gradle 9.9.1 / compileSdk 37 / targetSdk 36 / minSdk 26.** First attempt used AGP 8.13.2, but current AndroidX releases (core-ktx 1.19.1, Compose UI 1.12.1) require AGP ≥ 9.1 and compileSdk 37, so I moved to the current toolchain instead of pinning old libraries. AGP 9 provides built-in Kotlin (no `kotlin-android` plugin). targetSdk stays 36 until behaviour changes of 37 are checked on devices (Task 09). minSdk 26 is the provisional D02 default.
- Theme colours/shapes come from v4 tokens (`#2D5E3E` forest, `#F5F2EA` background, 8/10/12 dp radii). v4 fonts (Public Sans / IBM Plex Mono) are **not yet bundled**; system sans/mono used until Task 03.
- Android backup and device-transfer of app data are disabled (`data_extraction_rules.xml`): financial records must not leave via Google backup.
- The only screen is `SetupStatusScreen`, which reports facts about the build ("Not configured", "Not implemented yet (Task 02)", "Configured … (not contacted)"). There are no fake success labels, and a test asserts that.
- The server `/health` returns 503 within ~1 s when PostgreSQL is down (Hikari `connectionTimeout` lowered from the 30 s default after the compose drill showed a hang — see below).
- No dependency on prototype `support.js`: the APK contains no `.html`/`support.js` (checked with `unzip -l`).

### Environment (recorded, not assumed)

| Tool | Version / state |
|---|---|
| JDK | OpenJDK 21.0.12.1 |
| Gradle | wrapper 9.9.1 (all three builds) |
| Android SDK | Installed this session at `/opt/android-sdk` (cmdline-tools 16111833; `platforms;android-37.0`, `platforms;android-36`, `build-tools;37.0.0`, `36.0.0`, `platform-tools`). The user accepted the Android SDK licence. |
| AGP / Kotlin / KSP | 9.5.1 / 2.3.21 / 2.3.13 (KSP pinned, not yet applied — Room arrives in Task 02) |
| Compose BOM | 2026.09.00 |
| Ktor / Flyway / pgJDBC / HikariCP | 3.6.1 / 13.12.2 / 42.7.14 / 7.1.0 |
| Docker / Compose | 29.9.2 / v5.6.1; `postgres:17-alpine` (PostgreSQL 17.12), `eclipse-temurin:21-jre`, both pinned by digest |
| Emulator / KVM | **Not available** (`/dev/kvm` missing) |
| Network allow-list added | `dl.google.com` (SDK), `gcp.mirrors.maven.org` (Maven Central redirect for Robolectric jars) |

### Commands executed and results

Run from `produce-invoicing/`. All exit 0 on the final clean run.

```
cd shared  && ./gradlew clean test
cd android && ./gradlew clean assembleDevDebug assemblePilotDebug testDevDebugUnitTest testPilotDebugUnitTest \
                         lintDevDebug lintPilotDebug assembleDevDebugAndroidTest
cd server  && ./gradlew clean test installDist
```

| Suite | Tests | Result |
|---|---|---|
| `shared` `ServerEndpointTest` (URL validation: https-only unless allowed, no credentials/query/fragment, normalisation, "not configured") | 13 | 13 passed |
| `android` `AppConfigTest` ×2 flavors | 3 + 3 | passed |
| `android` `SetupStatusScreenTest` (Robolectric Compose, no fake success labels) ×2 flavors | 2 + 2 | passed |
| `android` `SetupStatusScreenshotTest` (Robolectric native graphics → PNG) ×2 flavors | 2 + 2 | passed |
| `server` `HealthRouteTest` (200 up / 503 down without leaking error detail / no other routes) | 3 | passed |
| `server` `ServerConfigTest` (fail fast without password, password never printed) | 3 | passed |
| `server` `MigrationIT` (Testcontainers PostgreSQL 17: migrate twice, idempotent seed, CHECK constraints) | 2 | passed |
| Android lint `devDebug` | — | 0 errors, 2 warnings: `OldTargetApi` (intentional, see above), `InsecureBaseConfiguration` (dev flavor only, intentional) |
| Android lint `pilotDebug` | — | 0 errors, 1 warning: `OldTargetApi` |
| `assembleDevDebugAndroidTest` | compiles | `SetupStatusInstrumentedTest` **not executed — BLOCKED: no device/emulator** |

Debug APKs (synthetic build, not for distribution; hashes change on every build):

| APK | Package | SHA-256 |
|---|---|---|
| `android/app/build/outputs/apk/dev/debug/app-dev-debug.apk` | `bb.produce.invoicing.dev`, minSdk 26, targetSdk 36, compileSdk 37 | `c19c63c1c2289d1bf8cdef0a2277721366829976248dd874ecc4a734918941a0` |
| `android/app/build/outputs/apk/pilot/debug/app-pilot-debug.apk` | `bb.produce.invoicing`, same SDK levels | `73a4f999c436b4c950b92331524b7c18fab5fba01f3bee3f8520130e7c788648` |

Permissions in the APK (`aapt2 dump badging`): `INTERNET` plus the AndroidX-generated `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. No Bluetooth or location permissions yet.

Note: these hashes were recorded before the screenshot test was added; that test is test-only and does not change APK contents, but rebuilds are not bit-for-bit reproducible, so re-hash any APK you distribute.

### Compose stack and backup/restore drill (synthetic data)

```
cp deploy/.env.example deploy/.env  # random POSTGRES_PASSWORD, PI_SEED_SYNTHETIC=true (dev only)
(cd server && ./gradlew installDist)
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

| Step | Observed |
|---|---|
| Boot | `db` healthy (pg_isready), then `api` healthy (`server --healthcheck`). Ports: `api 127.0.0.1:8080->8080`, `db 5432/tcp` (not published). |
| `GET /api/v1/health` | `200`, `X-PI-Api-Version: 1`, `{"status":"ok","database":"up","schemaVersion":"1","apiVersion":1}` |
| Host → `127.0.0.1:5432` | not reachable (expected) |
| API logs | config line shows `dbPassword=***`; no secret printed |
| `deploy/scripts/backup.sh` | dump written, `pg_restore --list` check passed, SHA-256 recorded |
| Simulated loss (`DELETE FROM business`) | 0 rows |
| `restore.sh <dump>` without `--yes` | refused, exit 2 |
| `restore.sh <dump> --yes` | checksum OK, API stopped → DB recreated → restored → API started |
| After restore | 1 row `DEMO | Demo Produce Co (SYNTHETIC TEST DATA)`; Flyway history `1 | t`; health 200 |
| `db` stopped (1st run) | **Defect found:** `/health` hung past 10 s (Hikari 30 s default) |
| Fix | Hikari `connectionTimeout=3000`, `validationTimeout=2000` |
| `db` stopped (after fix) | `503 {"status":"degraded","database":"down",…}` in < 1 s; back to 200 after `db` restarted |
| Teardown | `docker compose down -v`; local `.env` deleted |

### UI evidence
`docs/evidence/task00/setup-status-phone-pilot.png` (390×844 dp, pilot, not configured) and
`docs/evidence/task00/setup-status-tablet-dev.png` (1180×820 dp, dev, configured / not contacted). These are JVM (Robolectric) renders, **not** device screenshots.

### Blockers and open items

| ID | Item | Type |
|---|---|---|
| B00-1 | Instrumentation tests and install on real phone/tablet | **BLOCKED: hardware** (no device/emulator in this environment) |
| B00-2 | TLS for pilot devices (private office CA vs reverse proxy) and office host/OS/IP reservation/UPS | **BLOCKED: client decision** (docs/08 gate 6). Until resolved, `pilot` builds (https-only) cannot talk to the compose stack, by design. |
| B00-3 | Off-host encrypted backup target, schedule, retention | **BLOCKED: client decision**; documented as operator steps in the runbook |
| B00-4 | Printer models (58 mm), protocols | **BLOCKED: hardware/client** (Task 07) |
| B00-5 | Accountant-approved rounding, tax/VAT wording, invoice legal fields | **BLOCKED: client decision** (Task 01 uses provisional HALF_UP per line, flagged) |
| O00-1 | Bundle Public Sans / IBM Plex Mono (OFL) | Task 03 |
| O00-2 | Business code (`PRE`) and driver codes (`SUF`), starting sequences | Client to supply; synthetic `DEMO` used in seed only |
| O00-3 | Prototype features replaced: owner manual number override, "issuing waits for a connection" | Client should confirm (see `docs/13`) |

### Recommended next task
**Task 01 — Domain models and arithmetic** in `shared/domain`: BBD cents, BigDecimal quantities and units, line rounding, draft→issued state machine and immutable snapshots, V3 identity (`businessCode`, `driverCode`, per-driver sequence, UUID, `PRE-INV-SUF`, optional `PRE-N`), three timestamps, payment/stock/shift enums. Awaiting approval before starting.
