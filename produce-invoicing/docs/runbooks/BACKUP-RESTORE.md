# Office server — run, backup and restore runbook (initial, Task 00)

Scope: the docker compose stack in `deploy/` (API + PostgreSQL 17) on a **dedicated office host**
(mini-PC/PC/NAS/VM — never the owner's tablet). Cloud migration later reuses the same API and
Flyway migrations (docs/10).

## 1. First start

```bash
cp deploy/.env.example deploy/.env      # git-ignored
# edit deploy/.env: set POSTGRES_PASSWORD (openssl rand -base64 32) and, for LAN use,
# PI_API_BIND=<the host's reserved LAN address>. Keep PI_SEED_SYNTHETIC=false for pilot.
(cd server && ./gradlew installDist)
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
curl -s http://127.0.0.1:8080/api/v1/health   # {"status":"ok","database":"up",...}
```

- The database has **no published port** and is on an internal-only Docker network.
- Migrations run automatically at API start (Flyway). Never edit an applied migration.
- `restart: unless-stopped` brings both services back after a host reboot / power cut once Docker starts.
  A UPS for the office host is a client decision (docs/08 gate 6).

## 2. Backup

```bash
deploy/scripts/backup.sh                 # -> deploy/backups/produce-invoicing-<UTC>.dump + .sha256
```

The script verifies the archive is readable (`pg_restore --list`) and writes a SHA-256.
**Operator steps not automated yet (BLOCKED: client decision on backup target/owner):**

1. Encrypt the dump before it leaves the host, e.g. `gpg --symmetric --cipher-algo AES256 <file>.dump`
   (store the passphrase in the owner's password manager, not on the server).
2. Copy the encrypted file and its `.sha256` to a **separate device/location**.
3. Schedule daily (cron/systemd timer) and keep a retention policy agreed with the client/accountant.

## 3. Restore (destructive)

```bash
deploy/scripts/restore.sh deploy/backups/produce-invoicing-<UTC>.dump --yes
```

Checks the SHA-256 if present, stops the API, drops and recreates the database, restores with
`--exit-on-error`, then restarts the API. Without `--yes` it refuses (exit 2).

After a restore, any device that synced **after** the backup point still holds those events in
its outbox/ACK history; the sync protocol (Task 05) must treat re-upload as idempotent and the
register (`PRE-N`) must never be reallocated for an already-acknowledged UUID. Recovery procedure
for that case is defined in Task 05/09 — until then, do not restore a pilot server without review.

## 4. Drill evidence

The first drill (synthetic data) was executed on 2026-11-13 — see `IMPLEMENTATION_LOG.md`
("Backup/restore drill"). Repeat the drill before the pilot and after every schema migration.
