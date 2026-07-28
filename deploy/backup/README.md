# MySQL 8.4 备份与隔离恢复合同

## 当前状态

- 脚本防误连/防误删自动化：待 W9 本地测试
- 真实 MySQL 8.4 LTS 全量备份：`NOT_VERIFIED`
- 连续 binlog/PITR：`NOT_VERIFIED`
- 人工恢复演练：`NOT_VERIFIED`
- RPO ≤15 分钟、RTO ≤4 小时：`NOT_VERIFIED`

`scripts/release/mysql_backup.py` 与 `mysql_restore.py` 在无 `--execute` 时只输出计划且不连接数据库。当前仓库版本的执行模式只允许 loopback 上的 `shenzhou_hr_test`/`*_restore`，不提供生产绕过开关。

## 凭据

使用仓库外、当前用户所有、mode `0600`、非符号链接的 MySQL client option file：

```ini
[client]
host=127.0.0.1
port=13306
user=synthetic_restore_operator
password=<injected-outside-repository>
```

脚本解析并验证 host/port/user/password 是否存在，但不会输出值。不得使用 shell `source`、命令行密码、真实生产凭据或仓库内文件。

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
  --client-file /absolute/outside/repository/client.cnf \
  --output-dir /absolute/outside/repository/w9-recovery-YYYYMMDD-001

python3 scripts/release/mysql_restore.py \
  --execute \
  --run-id w9-recovery-YYYYMMDD-001 \
  --target-database shenzhou_hr_w9_recovery_restore \
  --confirm shenzhou_hr_w9_recovery_restore:w9-recovery-YYYYMMDD-001 \
  --client-file /absolute/outside/repository/client.cnf \
  --backup-manifest /absolute/outside/repository/w9-recovery-YYYYMMDD-001/w9-recovery-YYYYMMDD-001-backup-manifest.json \
  --dump /absolute/outside/repository/w9-recovery-YYYYMMDD-001/w9-recovery-YYYYMMDD-001-shenzhou_hr_test.sql \
  --verification-sql /absolute/integrated-release/w7-final-restore-verification.sql
```

恢复工具要求目标库事先不存在；只创建新的 `_restore` 库，从不 `DROP`，并在完成后保留沙箱供人工检查。

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
