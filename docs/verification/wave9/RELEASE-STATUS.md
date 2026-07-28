# W9 release status

- Harness state: `IMPLEMENTATION_IN_PROGRESS`
- Release verdict: `NOT_VERIFIED`
- Required upstream: `W7 FINAL`
- Explicit non-dependency: `W8`
- Baseline commit: `0c09fc375b1d2973915234e50a9aac10900446ca`
- Production connection: `PROHIBITED_IN_THIS_WORKTREE`

当前文件只登记 W9 独立实现边界，不是上线批准。即使本地静态检查和单元测试全部通过，仍必须在同步 W7 FINAL 后创建新的干净 integrated commit，并在同一 run ID 下补齐 MySQL 8.4 LTS、36 个月容量/50 并发、真实浏览器矩阵、人工 PITR/RPO/RTO 与 Ubuntu 24.04 原生部署/回滚证据。

证据模板见 `release-manifest.template.json`。最终 manifest 的每个叶子只允许 `PASS`、`FAIL` 或 `NOT_VERIFIED`；任一 `FAIL` 阻断发布，任一必需门 `NOT_VERIFIED` 禁止宣称生产上线通过。
