#!/usr/bin/env bash
# Restores a dump produced by backup.sh into the running stack's database.
# DESTRUCTIVE: replaces the current database contents. The API is stopped first.
# Usage: deploy/scripts/restore.sh <file.dump> --yes
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
file="${1:?usage: restore.sh <file.dump> --yes}"
[[ "${2:-}" == "--yes" ]] || { echo "Refusing to restore without --yes (this replaces all data)"; exit 2; }
[[ -f "$file" ]] || { echo "No such file: $file"; exit 2; }
if [[ -f "$file.sha256" ]]; then
  (cd "$(dirname "$file")" && sha256sum --check --quiet "$(basename "$file").sha256")
  echo "Checksum OK"
else
  echo "WARNING: no $file.sha256 alongside the dump; integrity not verified"
fi
compose=(docker compose -f "$here/docker-compose.yml" --env-file "$here/.env")
"${compose[@]}" stop api
"${compose[@]}" exec -T db sh -c 'dropdb -U "$POSTGRES_USER" --if-exists --force "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" "$POSTGRES_DB"'
"${compose[@]}" exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --exit-on-error' < "$file"
"${compose[@]}" start api
echo "Restore complete. Check: curl -s http://<PI_API_BIND>:<PI_API_PORT>/api/v1/health"
