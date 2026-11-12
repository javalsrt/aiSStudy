# AGENTS.md

## 推送 / 同步 GitHub

- 用户说“推送”“同步 GitHub”“同步仓库”时，**不得只执行 `git push`**。
- 必须先运行项目根目录的 `sync-github.ps1`（也可双击 `sync-github.bat`），由脚本完成：
  1. `mysqldump` 导出当前数据库到 `init-db/znxsgltest.sql`
  2. `git add -A`
  3. `git commit`
  4. `git push origin HEAD`
- 数据库连接默认 `localhost / root / 123456 / znxsglTest`，可用环境变量
  `DB_HOST / DB_USER / DB_PASSWORD / DB_NAME` 覆盖；`mysqldump` 路径可用 `MYSQLDUMP` 指定。
- 脚本先导出到 `.tmp`，成功后才替换正式 SQL；导出失败会中止同步，不得推送不完整的数据。
- 完成后向用户报告提交号、数据库导出大小与推送结果。
