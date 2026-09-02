#!/usr/bin/env bash
set -euo pipefail
umask 077

if [ "$#" -ne 2 ] || [ "$1" != "--confirm" ]; then
  echo "Usage: $0 --confirm <backup-directory>" >&2
  exit 2
fi

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
backup_dir=$(cd "$2" && pwd)
cd "$project_dir"

for file in database.dump app-data.tar.gz manifest.txt SHA256SUMS; do
  [ -f "$backup_dir/$file" ] || { echo "Missing backup file: $file" >&2; exit 2; }
done
(cd "$backup_dir" && sha256sum -c SHA256SUMS)

# Always leave a recoverable checkpoint before replacing either half.
pre_restore="backups/pre-restore-$(date -u +%Y%m%dT%H%M%SZ)"
"$project_dir/scripts/backup-compose.sh" "$pre_restore" --leave-stopped
docker compose stop newsclaw-server >/dev/null

echo "Restoring database; pre-restore checkpoint: $pre_restore"
docker compose exec -T newsclaw-postgres sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" exec pg_restore --clean --if-exists --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  <"$backup_dir/database.dump"

docker run --rm \
  -v newsclaw-server-data:/target \
  -v "$backup_dir:/backup:ro" \
  alpine:3.20 sh -ec \
  'find /target -mindepth 1 -depth -delete; tar -C /target -xzf /backup/app-data.tar.gz'

docker compose up -d newsclaw-server >/dev/null
echo "Restore complete. Verify /actuator/health/readiness before serving traffic."
