# MySQL 8.4 备份与隔离恢复合同

## 当前状态

- 脚本防误连/防误删自动化：W9 本地合同测试已通过
- 真实 MySQL 8.4 LTS 全量备份：`NOT_VERIFIED`
- 连续 binlog/PITR：`NOT_VERIFIED`
- 人工恢复演练：`NOT_VERIFIED`
- RPO ≤15 分钟、RTO ≤4 小时：`NOT_VERIFIED`

`scripts/release/mysql_backup.py` 与 `mysql_restore.py` 在无 `--execute` 时只输出计划且不连接数据库。当前仓库版本的执行模式只允许 loopback 上的 `shenzhou_hr_test`/`*_restore`，不提供生产绕过开关。

## 凭据与文件权限

所有 MySQL client option file 必须在仓库外，是当前用户所有、mode
`0600`、非符号链接的普通文件，其父目录必须由当前用户所有且 mode `0700`。
文件只允许一个 `[client]` section，且只允许
`host`、`port`、`user`、`password` 四个各出现一次的非空键；include/directive、
未知键和重复键都会被拒绝。`host` 必须是 literal loopback 地址，连接命令还会
使用 `--defaults-file`/`--no-login-paths` 排除系统、用户 option file 和 login
path 的影响，并显式固定 TCP、host 和 port。
执行程序同样固定为绝对路径；Ubuntu 默认使用 `/usr/bin/mysql` 与
`/usr/bin/mysqldump`，如批准安装位置不同，必须通过 `--mysql-bin` /
`--mysqldump-bin` 传入经审核的绝对路径，不能依赖 `PATH` 查找。

```ini
[client]
host=127.0.0.1
port=13306
user=<injected-outside-repository>
password=<injected-outside-repository>
```

不得使用 shell `source`、命令行密码、真实生产凭据或仓库内文件，脚本也不会
输出凭据值。备份输出目录必须是仓库外、当前用户所有、非符号链接且 mode
`0700`；dump、manifest 和中间文件以独占方式创建为 `0600`，成功后原子发布，
失败时清理未发布的 partial，不覆盖任何既有文件。

完整恢复演练使用三个相互独立、但连接同一批准 loopback endpoint 的账号：

1. `--client-file`：admin 账号，只用于验证目标 authority、确认目标库不存在并创建新的恢复库；
2. `--import-client-file`：只具有精确目标 `*_restore` schema 所需权限的导入账号，admin 不执行导入；
3. `--verification-client-file`：只具有精确目标 schema 的 `SELECT`/`SHOW VIEW` 权限的只读核验账号。

导入和只读账号不得等于 admin，二者也不得互相复用。脚本会读取并验证其
`SHOW GRANTS`，拒绝跨 schema 权限、角色、proxy、`GRANT OPTION` 或超出允许
集合的权限。脚本允许不提供只读账号与核验 SQL，但此时自动恢复核验仍为
`NOT_VERIFIED`，不能作为完整演练证据。

## 默认计划

```bash
python3 scripts/release/mysql_backup.py
python3 scripts/release/mysql_restore.py
```

## 隔离演练形态

取得批准的非生产 MySQL 8.4 LTS 与仓库外 client file 后：

```bash
python3 scripts/release/mysql_backup.py \
  --execute \
  --run-id w9-recovery-YYYYMMDD-001 \
  --database shenzhou_hr_test \
  --client-file /absolute/outside/repository/backup-admin.cnf \
  --output-dir /absolute/outside/repository/w9-recovery-YYYYMMDD-001 \
  --expected-server-uuid "$W9_BACKUP_SERVER_UUID" \
  --expected-environment-id "$W9_BACKUP_ENVIRONMENT_ID"

python3 scripts/release/mysql_restore.py \
  --execute \
  --run-id w9-recovery-YYYYMMDD-001 \
  --target-database shenzhou_hr_w9_recovery_restore \
  --confirm "shenzhou_hr_w9_recovery_restore:w9-recovery-YYYYMMDD-001:${W9_BACKUP_SERVER_UUID}:${W9_BACKUP_ENVIRONMENT_ID}:${W9_RESTORE_SERVER_UUID}:${W9_RESTORE_ENVIRONMENT_ID}:${W9_DUMP_SHA256}" \
  --client-file /absolute/outside/repository/restore-admin.cnf \
  --import-client-file /absolute/outside/repository/restore-import.cnf \
  --verification-client-file /absolute/outside/repository/restore-read-only.cnf \
  --backup-manifest /absolute/outside/repository/w9-recovery-YYYYMMDD-001/w9-recovery-YYYYMMDD-001-backup-manifest.json \
  --dump /absolute/outside/repository/w9-recovery-YYYYMMDD-001/w9-recovery-YYYYMMDD-001-shenzhou_hr_test.sql \
  --expected-source-database shenzhou_hr_test \
  --expected-dump-sha256 "$W9_DUMP_SHA256" \
  --expected-backup-server-uuid "$W9_BACKUP_SERVER_UUID" \
  --expected-backup-environment-id "$W9_BACKUP_ENVIRONMENT_ID" \
  --expected-restore-server-uuid "$W9_RESTORE_SERVER_UUID" \
  --expected-restore-environment-id "$W9_RESTORE_ENVIRONMENT_ID" \
  --verification-sql /absolute/outside/repository/w7-final-restore-verification.sql \
  --verification-sql-sha256 "$W9_VERIFICATION_SQL_SHA256"
```

示例中的 `W9_*` 值必须由仓库外批准流程注入，不得使用仓库默认值。`--confirm`
由七个逻辑 authority 值按以下顺序完整拼接：
`<target>:<run-id>:<backup-server-uuid>:<backup-environment-id>:<restore-server-uuid>:<restore-environment-id>:<dump-sha256>`；
环境标识必须使用稳定的小写 `nonprod:<id>`，server UUID 必须是 canonical
lowercase UUID。token 的每个逻辑值都必须与命令行批准值完全一致。

恢复前会同时校验 manifest 的 run ID、源库、MySQL 8.4、源 endpoint、
`server_uuid`、environment marker、数据库 identity、binlog/GTID 元数据、dump
文件名/长度/SHA-256 和凭据日志卫生；manifest 与 dump 必须位于同一安全
`0700` 目录且各为 `0600`，实际 dump SHA-256 还必须等于独立提供的批准摘要。
备份会在实际 `mysqldump` 前后重新读取并核对 authority，同时记录起止
GTID/binlog 位置；任一次不一致都不会发布 dump/manifest。manifest 发布失败时，
本次刚生成的最终 dump 会被清理，避免留下无法绑定来源的敏感孤儿文件。
提供核验 SQL 时，该文件也必须在仓库外安全目录、mode `0600`，并与独立批准的
`--verification-sql-sha256` 一致。

恢复工具要求目标库事先不存在；只创建新的 `_restore` 库，从不 `DROP`，使用
导入账号以非交互 `--binary-mode`（禁用绝大多数 client command）完成导入，
随后才可使用独立只读账号执行
批准的核验 SQL。创建、导入和核验前后都会重新核对 authority 与受限账号；
任一步失败时明确报告新建沙箱可能残留，保留现场供人工检查，禁止自动复用或
自动删除。完整成功后同样保留沙箱供人工检查。

## 季度人工演练清单

1. 冻结 run ID、integrated commit、W7 FINAL marker、MySQL `VERSION()`/`server_uuid`、Flyway 历史和现网基线摘要。
2. 证明全量备份 SHA、加密存储责任、保留策略与 binlog 连续归档；记录最后可恢复事务 UTC 时间。
3. 在全新隔离 MySQL 8.4 LTS 实例恢复全量后，按目标 UTC/GTID 应用 binlog。
4. 执行 W7 FINAL 提供的业务验证 SQL：Flyway、表/索引/约束、规则版本、月结 snapshot、同步水位、关键计数/哈希。
5. 由考勤负责人抽查合成金标准，确认规则、日结果、月结清单和报表版本一致。
6. 记录故障时刻到最后恢复事务的间隔（RPO）及开始响应到业务验收完成的总时长（RTO）。
7. 只有 RPO ≤15 分钟、RTO ≤4 小时且全部勾稽为零差异时，`recovery_drill` 才可为 `PASS`。
8. 销毁演练环境需另行审批；仓库脚本不自动删除数据库或证据。

单次 dump 成功、fake client 测试或无业务验证 SQL 的导入均不能满足最终恢复门。
