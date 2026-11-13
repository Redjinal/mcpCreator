# Produce Invoicing — Claude Code MVP Handoff v3

> **Implementation status:** Tasks 00–03 complete (scaffold; domain and V3 numbering; Room offline storage with atomic issue; responsive field UI) — see `IMPLEMENTATION_LOG.md`. Sign-in is a developer stub until Task 04; no sync or printing yet.

## Repository layout (implementation)

| Path | Contents |
|---|---|
| `gradle/libs.versions.toml` | Pinned versions shared by every build |
| `shared/` | Pure Kotlin domain shared by app and server (composite build) |
| `android/` | Android app (`:app`, `:core:data`, `:core:printing`, `:core:sync`), flavors `dev` / `pilot` |
| `server/` | Office LAN API (Ktor, Flyway, PostgreSQL) |
| `deploy/` | Docker Compose stack, `.env.example`, backup/restore scripts |
| `api/openapi/v1.yaml` | Versioned API contract |
| `docs/00`, `docs/13`, `docs/runbooks/` | Prototype inventory, traceability matrix, server runbook |

## Build & run

Prerequisites: JDK 21, Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`
(set `sdk.dir` in `android/local.properties` or `ANDROID_HOME`), Docker for the server stack and its integration tests.

```bash
cd shared  && ./gradlew test
cd android && ./gradlew assembleDevDebug assemblePilotDebug testDevDebugUnitTest testPilotDebugUnitTest lintPilotDebug
cd server  && ./gradlew test installDist
```

Office API URL for the app is build configuration, empty by default (the app then shows "Not configured"):

```bash
./gradlew assembleDevDebug -Ppi.apiBaseUrl.dev=http://<dev-workstation-host>:8080
./gradlew assemblePilotDebug -Ppi.apiBaseUrl.pilot=https://<office-server-host>
```

Server stack: see `docs/runbooks/BACKUP-RESTORE.md`.

---

**Approved scope (13 Nov 2026, V3 numbering amendment):** Android Kotlin/Compose, offline-first invoice issuing, 58 mm thermal printing across multiple verified Bluetooth/Wi-Fi printer models, office LAN sync with cloud-ready interface, payment method + PAID/UNPAID, vehicle stock loads/sales/returns and end-of-day reconciliation.

## Contents
- `CLAUDE.md`: binding engineering instructions and invariants.
- `docs/01–07`: audited prototype and original baseline (read v2 overrides below).
- `docs/08–12`: **approved decisions, payment/stock policies, and LAN/printing architecture**; where conflict exists, these supersede earlier provisional scope.
- `tasks/00–14`: sequential implementation checkpoints with acceptance gates; 10 is payments, 11 is stock, 12 is close, 13 is pilot.
- `reference/Produce Invoicing Prototype.zip`: supplied v1–v4, invoice document and support assets; **v4 is a UX reference, not runnable Android source**.

## How to start
Create a new Git repo and extract this pack at the project root. Open Claude Code in that directory and give it:

> Read `CLAUDE.md`, all `docs/01–12`, and `tasks/00-REPOSITORY-BOOTSTRAP.md`. Implement **only task 00**, add tests, execute build checks and document evidence in IMPLEMENTATION_LOG.md. Do not advance without a checkpoint. Treat the user-approved v3 decisions as authoritative.

After acceptance: `Implement tasks/01-DOMAIN-AND-MONEY.md, with all v2 constraints; stop at its acceptance gate.` Repeat by task number through task 13.

## Delivery
Reproducible Android project and locally installable debug/test APK; secured local API + PostgreSQL with backups/restore; no cloud account needed to issue invoices. Hardware compatibility matrix, 58 mm print samples, mock and real printer tests, stock/payment reconciliation reports, device onboarding, pilot guide and rollback/recovery plan. Never mark an untested physical printer as supported.

## Critical client inputs before pilot
Physical printer models; office LAN server; approved sample invoice/VAT wording; stock unit conversions and return dispositions; actual payment methods and rules for marking paid. Use honest test placeholders until approved, never invent regulatory details.

## V3 mandatory invoice identities
- Customer-facing number: `BGI-100-AB`, `BGI-100-FD` are both valid; each driver has an independent sequence.
- Separate server-assigned register reference: `BGI-100`, `BGI-101` (registration order, not purported issue-time order).
- Permanent UUID links the two; no renumbering. See `docs/11-INVOICE-NUMBERING-AND-REGISTER.md` for authoritative implementation/edge cases, and `docs/12-NUMBERING-TEST-MATRIX.md` for verification. These explicitly supersede the old non-overlapping number-block approach.
- **Start prompt:** `Read CLAUDE.md, README.md, docs/01–12 and tasks/00-REPOSITORY-BOOTSTRAP.md. Inspect reference/Produce Invoicing Prototype.zip. Implement Task 00 ONLY, verify your work, write IMPLEMENTATION_LOG.md, and STOP for review. Follow the approved V3 dual-identifier invoice numbering, never the earlier number-block approach.`
