# W9 发布加固与验收索引

本目录是 V1.9 W9 的本地 harness 与最终 integrated release evidence 交接入口。它不是上线批准。

## 当前边界

- Harness state: `READY_LOCAL_OFFLINE`
- Release verdict: `NOT_VERIFIED`
- Required upstream: `W7 FINAL`
- Explicit non-dependency: `W8`
- Production connection: `PROHIBITED`
- MySQL/network behavior: 所有危险脚本默认仅生成 plan；任何 execute 都受 loopback、库名 allowlist、仓库外 `0600` 凭据和精确确认令牌保护

## 入口

| 目标 | 入口 | 当前结论 |
| --- | --- | --- |
| W9 本地总编排 | `scripts/release/verify_wave9.py` | Harness 可本地验证；发布固定 `NOT_VERIFIED` |
| Release evidence | `scripts/release/release_evidence.py` | 严格 `PASS/FAIL/NOT_VERIFIED`，W8 非依赖 |
| 威胁检查 | `scripts/release/verify_threats.py` | 静态可本地运行；动态攻击矩阵待 W7 FINAL |
| 性能/容量 | `scripts/release/http_load.py` | 仅 loopback；50 并发与 36 个月 POC 待批准环境 |
| 原生部署 | `scripts/release/native_preflight.py` | 只读模板/制品预检；不安装、不切流 |
| 备份恢复 | `scripts/release/mysql_backup.py`、`mysql_restore.py` | 默认 plan；人工 MySQL 8.4 恢复演练待执行 |
| 浏览器矩阵 | `scripts/release/browser_matrix.py` | 462 个 execution cells；真实设备证据待执行 |

## 证据与运行说明

- [`RELEASE-STATUS.md`](RELEASE-STATUS.md)：当前结论和不可越界项
- [`LOCAL-VERIFICATION.md`](LOCAL-VERIFICATION.md)：本 worktree 的真实命令与结果
- [`release-manifest.template.json`](release-manifest.template.json)：最终 release manifest 模板
- [`npm-audit-applicability.md`](npm-audit-applicability.md)：前端依赖告警的适用性与 A/B 回归记录
- [`browser/README.md`](browser/README.md)：真实浏览器/视口/角色执行规则
- [`browser/browser-matrix.template.json`](browser/browser-matrix.template.json)：待 W7 FINAL 后填充的矩阵
- [`../../../deploy/backup/README.md`](../../../deploy/backup/README.md)：RPO/RTO、PITR 和恢复演练 runbook

## 最终集成重跑

只有同步 W7 FINAL 并创建干净 integrated commit 后，才允许在批准的隔离环境补齐动态威胁、MySQL 8.4、36 个月容量/50 并发、真实浏览器、人工恢复和 Ubuntu 原生部署/回滚证据。所有 evidence 必须共享同一 run ID、commit、environment ID，并由 SHA-256 绑定；任一 `FAIL` 阻断发布，任一必需门 `NOT_VERIFIED` 禁止宣称上线通过。
