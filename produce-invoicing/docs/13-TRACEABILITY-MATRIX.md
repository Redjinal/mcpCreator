# Traceability matrix — v4 prototype → native MVP (Task 00, updated Tasks 01–03, 2026-11-13)

Source inventory: `docs/00-V4-PROTOTYPE-INVENTORY.md`. Binding rules: `CLAUDE.md`, docs 08–12.
"Status" is the state **in this repository today**. Nothing below is implemented unless it says so.

Legend: **Scaffold** = module/boundary exists, no behaviour. **Not started** = planned in the named task. **Replaced** = prototype behaviour deliberately not ported. **BLOCKED** = needs hardware or a client decision.

## 1. Screens

| v4 identifier | Native screen (Compose, `:app`) | Data / domain layer | Task | Status | Change vs prototype |
|---|---|---|---|---|---|
| — (none) | `SetupStatusScreen` (bootstrap diagnostics) | `AppConfig` → `ServerEndpoint` (`:shared:domain`) | 00 | **Implemented** | New. Shows only facts about the build; no success states. Replaced by the real shell in Task 03. |
| Presenter role switch (`role` d1/d2/owner) | Sign-in gate behind `AuthGateway` (dev: synthetic demo users; pilot: "not provisioned") | Auth interface, device lease | 03 (stub), 04 | **Stub implemented**; real auth Task 04 | **Replaced**: real per-user auth + server-enforced RBAC; no demo role switch in pilot builds. |
| Shell: sidebar (`isWide` ≥1000) / bottom nav (`navAll`) | `NavigationSuiteScaffold` (bottom bar / rail by window size) + split panes ≥840 dp | Navigation Compose | 03 | **Implemented** | Keep breakpoints as a starting point (600/1000/1300 dp); validate on real devices. |
| `screen:'create'` `step:'edit'` | `DraftEditorScreen` (customer, lines, units, qty stepper, price, zero-price confirm, undo remove, stock warnings) | Draft repository (Room, autosave) | 02, 03 | **Implemented** (Task 03) | Persistent draft (survives process death), numeric keypad for weight/price, totals always visible. |
| `step:'review'` / live preview pane | Review dialog; live preview pane at ≥840 dp | Invoice snapshot builder | 03 | **Implemented** | Preview must show `PRE-INV-SUF` "assigned when issued", never a guessed number. |
| `sheet:'customer'` | `CustomerPicker` (search + create offline) | Customer DAO, outbox CUSTOMER_CREATED | 03 | **Implemented** (no "recent" yet) | "Recent" was *Proposed* in v4 — confirm with client. |
| `sheet:'product'` | `ProductPicker` (per-unit add, vehicle stock) + custom line | Catalogue + stock ledger | 03 | **Implemented** | Custom-line policy and price override need D06 confirmation. |
| `modal.type:'issue'` | Issue confirmation dialog | **Atomic Room transaction**: driver sequence ++, UUID, snapshots, SALE_DEBIT, audit, outbox | 02, 04, 11 | Data layer **implemented** (Task 02); dialog not started | Double-tap guard via action token (N18). Number = `PRE-INV-SUF`, not `INV-00129`. |
| `modal.type:'offlineIssue'` ("Issuing waits for a connection") | — | — | — | **Replaced** | Offline issuing is a core requirement (A1/A2); drivers issue fully offline under a fenced lease. |
| `screen:'invoices'` (+ filters, range popover) | `InvoicesScreen` (search number/office ref/customer/driver/UUID; filters; list+detail split ≥840 dp) | `FieldReadModel.invoiceRows` (driver sees own only) | 03, 08 | **Implemented** (date-range filter: Task 08) | Separate badges for issue / sync / print status (docs/06). |
| `showDaily` (owner daily review) | Owner daily summary per driver | Server daily summary; local pending marked | 08, 12 | Not started | Totals must not double count payments (docs/09). |
| `screen:'detail'` | `InvoiceDetailContent` (separate issue/payment/sync/print badges, office ref labelled internal, mark paid, owner reversal/void) | Immutable snapshot + payment events | 03, 06, 10 | **Implemented** | Shows `Invoice: BGI-100-AB`, `Driver: <name>`, and `Internal register reference` only when synced. |
| `screen:'print'` + `modal.type:'print'` (phases select/sending/done/unavailable/uncertain) | 58 mm print preview + print flow | `:core:printing` renderer, `PrinterGateway`, persisted attempts | 06, 07 | **Preview implemented** (`Receipt58`, original/COPY); printing disabled until Tasks 06–07 | **Replaced**: v4 timer-driven fake outcomes. Real states prepared/attempted/transport-sent/uncertain; COPY on reprint. Paper is **58 mm** (v4 showed 80 mm illustrative). |
| `modal.type:'sync'` + scenario banners (offline/partial/conflict) | Sync health screen + conflict UI | `:core:sync`, outbox, WorkManager | 05 | Scaffold (`:core:sync` empty) | **Replaced**: v4 retry flips everything to "synced". Real status comes only from server ACKs. No last-write-wins on issued records. |
| `screen:'customers'` / `'customer'` | `CatalogueScreen` customers tab, `CustomerDetailScreen` (history, new invoice) | Customer DAO + offline create | 03, 08 | **Implemented** (owner edit: Task 08) | Validation rules from v4 (`custErrs`) are a reasonable starting point. |
| `screen:'products'` / `'product'` | `CatalogueScreen` products tab (read-only, vehicle stock) | Catalogue + conversions + ledger | 03, 08, 11 | **Implemented read-only** (owner edit: Task 08) | v4 float prices → integer cents; v4 "no unit conversion" → explicit per-product conversion records (docs/09). Delete only if unused. |
| `screen:'settings'` | `SettingsScreen` (session, lease, business read-only, printers, sync/storage diagnostics) | Business, lease, printer profiles | 03, 06, 08 | **Implemented read-only** | **Replaced**: v4 `prefix` + `nextSeq` editor. Business code (PRE) and per-driver starting sequence are owner provisioning actions with audit (docs/11). |
| Owner "Override number" (`numberOverride`) | — | — | — | **Replaced** | Manual invoice numbers conflict with V3 immutable per-driver sequences. Escalate to client if still wanted. |
| QuickBooks manual tick (`qbManual`) | Owner "entered in QuickBooks" mark | Audited owner event | 08 | Not started | Manual status only (D10). Future-phase mapping/queue in v4 is fictional and not ported. |
| — (new in V2) | Shift / load stock, stock on vehicle, return intake, end-of-shift count | Stock ledger, shifts | 11, 12 | Not started | Not in v4. |
| — (new in V2) | Payment method + PAID/UNPAID | Append-only `payment_event` | 10 | Not started | Not in v4. No payment processing. |
| Toast + Undo for line removal | Snackbar with Undo (draft only) | Draft repository | 03 | Not started | Undo never applies to issued records. |
| Presenter bar, notes drawer, dataMode, "Reset sample data" | — | — | — | **Replaced** | Prototype-only. No demo controls in pilot build (docs/01). Synthetic seed exists server-side only behind `PI_SEED_SYNTHETIC=true`. |

## 2. Data objects

Task 01 status: every row below now has a **pure domain model with unit tests** in `shared/domain` (`bb.produce.invoicing.domain.*`). Task 02: all of these are persisted in Room (`android/core/data`, schema v2) with an atomic issue transaction; PostgreSQL storage, UI and sync are still not started.

| v4 object / field | Native representation | Task |
|---|---|---|
| `qty: string`, `price: string` (editing), product `price: float` | Quantity: `BigDecimal` (scale 3) + unit; money: `Long` cents + `BBD` | 01 |
| `cents(l)` = round(qty×1000)·round(price×100)/1000, half-up per line | Same rounding intent (HALF_UP to cents per line) — **subject to accountant approval** (docs/03) | 01 |
| `number` = `prefix + padStart(5)` from one shared `nextSeq` | `PRE-INV-SUF` (per-driver durable sequence) + server `PRE-N` register ref; UUID identity | 01, 02, 04, 05 |
| `customer`, `business` snapshots on issue | Immutable snapshots incl. driver name, catalogue version, tax snapshot, actor, device, 3 timestamps | 01, 02 |
| `printStatus`, `printLog` | `print_attempt` rows; print status orthogonal to issue/sync | 06 |
| `sync` per record | Outbox event + ACK; sync state PENDING/SYNCING/SYNCED/CONFLICT/FAILED_RETRYABLE | 05 |
| `issuedAt` ISO with `-04:00` | UTC instants; display in `America/Barbados` | 01 |
| `settings.units` list (no conversion) | Units + versioned per-product stock conversions | 01, 11 |

## 3. Config & environments (implemented in Task 00)

| Item | Where | Status |
|---|---|---|
| Android flavors `dev` (cleartext allowed, `.dev` app id) / `pilot` (HTTPS only) | `android/app/build.gradle.kts`, `src/{dev,pilot}/res/xml/network_security_config.xml` | **Implemented** |
| API base URL per flavor from Gradle property `pi.apiBaseUrl.<flavor>`; empty ⇒ "Not configured" | `android/gradle.properties`, `AppConfig`, `ServerEndpoint` | **Implemented**, unit tested |
| Office API skeleton `/api/v1/health` | `server/` (Ktor + Flyway + PostgreSQL) | **Implemented**, tested |
| Versioned API schema | `api/openapi/v1.yaml` | **Implemented** (health only; rest listed as planned) |
| Docker Compose (API + PostgreSQL, healthchecks, no DB port, safe env template) | `deploy/` | **Implemented**, booted and verified |
| Backup/restore runbook + scripts | `docs/runbooks/BACKUP-RESTORE.md`, `deploy/scripts/` | **Implemented**, drill executed locally |
| TLS for pilot devices | — | **BLOCKED: client decision** (office CA vs reverse proxy, office host) |
| Device provisioning of URL (instead of build property) | — | Not started (Task 04) |

## 4. Unfinished functionality (all P0, by task)

01 domain/money/identity · 02 Room + atomic issue · 03 UI · 04 auth/RBAC/leases · 05 sync/register · 06 print abstraction · 07 real printers (**BLOCKED: hardware**) · 08 owner admin/reporting · 09 hardening · 10 payments · 11 stock/returns · 12 close · 13 pilot APK · 14 numbering E2E.
