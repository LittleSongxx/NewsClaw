# Docker Compose backup and restore

PostgreSQL and `/app/data` form one application checkpoint. The backup script stops the application writer, then saves a custom-format database dump and the data volume together.

```bash
./scripts/backup-compose.sh
./scripts/restore-compose.sh --confirm backups/20260901T120000Z
```

Restore verifies SHA-256 and first creates a `backups/pre-restore-*` rollback checkpoint. Backups contain conversations, files, and encrypted credentials; keep them encrypted and access-controlled off-host, schedule them daily, and test restoration regularly. A Docker named volume alone is not a backup.
