#!/usr/bin/env bash
# Creates a PostgreSQL custom-format dump of the running stack.
# Usage: deploy/scripts/backup.sh [output-dir]   (default: deploy/backups)
# The dump contains business records: encrypt it and copy it OFF this host
# (see docs/runbooks/BACKUP-RESTORE.md). This script does not do that for you.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$here/backups}"
mkdir -p "$out"
chmod 700 "$out"
compose=(docker compose -f "$here/docker-compose.yml" --env-file "$here/.env")
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="$out/produce-invoicing-$stamp.dump"
umask 077
"${compose[@]}" exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner' > "$file"
# Verify the archive is readable before reporting success.
"${compose[@]}" exec -T db pg_restore --list < "$file" > /dev/null
(cd "$out" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256")
echo "Backup written: $file"
echo "SHA-256: $(cut -d' ' -f1 "$file.sha256")"
