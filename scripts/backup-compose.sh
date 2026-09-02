#!/usr/bin/env bash
set -euo pipefail
umask 077

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$project_dir"

backup_dir=${1:-"backups/$(date -u +%Y%m%dT%H%M%SZ)"}
leave_stopped=${2:-}
if [ -n "$leave_stopped" ] && [ "$leave_stopped" != "--leave-stopped" ]; then
  echo "Usage: $0 [backup-directory] [--leave-stopped]" >&2
  exit 2
fi
mkdir -p "$backup_dir"
backup_dir=$(cd "$backup_dir" && pwd)
if find "$backup_dir" -mindepth 1 -print -quit | grep -q .; then
  echo "Refusing to overwrite non-empty backup directory: $backup_dir" >&2
  exit 2
fi

server_was_running=false
if [ -n "$(docker compose ps --status running -q newsclaw-server)" ]; then
  server_was_running=true
fi

restart_server() {
  if [ "$server_was_running" = true ] && [ "$leave_stopped" != "--leave-stopped" ]; then
    docker compose up -d newsclaw-server >/dev/null
  fi
}
trap restart_server EXIT

# Stop the only application writer so the database dump and file volume describe
# one application-level checkpoint.
docker compose stop newsclaw-server >/dev/null

docker compose exec -T newsclaw-postgres sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" exec pg_dump --format=custom --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  >"$backup_dir/database.dump"

docker run --rm \
  -v newsclaw-server-data:/source:ro \
  -v "$backup_dir:/backup" \
  alpine:3.20 tar -C /source -czf /backup/app-data.tar.gz .

{
  echo "created_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "compose_project=newsclaw"
  echo "database_format=pg_dump_custom"
  echo "app_data_volume=newsclaw-server-data"
  echo "git_revision=$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
} >"$backup_dir/manifest.txt"

(cd "$backup_dir" && sha256sum database.dump app-data.tar.gz manifest.txt >SHA256SUMS)
echo "Backup complete: $backup_dir"
