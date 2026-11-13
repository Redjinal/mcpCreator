# Implementation Log — Produce Invoicing MVP

Binding instructions: `CLAUDE.md` (V3). Each checkpoint records decisions, exact commands, results and blockers.
Nothing is marked done on the basis of generated code alone.

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
