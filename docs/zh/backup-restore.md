# Docker Compose 备份与恢复

数据库和 `/app/data` 必须作为同一个检查点处理。仓库脚本会先停止应用写入，再同时保存 PostgreSQL 自定义格式 dump 与数据卷归档。

```bash
./scripts/backup-compose.sh
./scripts/restore-compose.sh --confirm backups/20260901T120000Z
```

恢复前会自动创建 `backups/pre-restore-*` 回退点，并校验 SHA-256。恢复失败时保持应用停止，先使用回退点恢复，不要带着不一致的数据库和文件卷启动。

备份包含会话、文件和加密后的凭据，脚本以 `umask 077` 创建目录；传到异机或对象存储时仍应使用服务端加密和独立访问控制。生产环境应由宿主机定时器每日调用备份脚本，并定期在隔离环境执行恢复演练。仅保留 Docker named volume 不构成备份。
