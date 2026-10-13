# W9 release status

- Harness state: `READY_LOCAL_OFFLINE`
- Release verdict: `NOT_VERIFIED`
- Product regression: `PASS_LOCAL_INTEGRATED`（2026-07-29 集成分支本地全量回归通过）
- Required upstream: `W7 FINAL`（当前集成分支已合入/等价包含）
- Explicit non-dependency: `W8`
- Baseline commit: `0c09fc375b1d2973915234e50a9aac10900446ca`
- Production connection: `PROHIBITED_IN_THIS_WORKTREE`

当前文件不是上线批准。2026-07-28 W9 独立 worktree 曾记录前端异步路由回归
失败；该历史保留在 `LOCAL-VERIFICATION.md`。2026-07-29 集成分支实际完成：
后端 528/528、前端 44 个测试文件通过（2 个 skipped）/351 个测试通过（4 个
skipped）、typecheck/lint/生产构建通过、W8 22/22 及两个 verifier 通过、W9
75/75 通过，故产品本地回归现记为 `PASS_LOCAL_INTEGRATED`。

该 PASS 不等于生产可发布。仍必须使用同一干净 integrated commit、run ID 和
environment ID，补齐批准的 MySQL 8.4 LTS、36 个月容量/50 并发、真实浏览器
矩阵、人工 PITR/业务勾稽/RPO/RTO、Ubuntu 24.04 原生部署/回滚，以及真实 OA
只读数据源和得力 E+ 端到端联调证据。上述外部门未完成前，Release verdict
保持 `NOT_VERIFIED`。

浏览器矩阵当前采取 fail-closed：即使本地补齐 462 项执行记录，在尚未接入受信
CI/设备农场的签名 attestation 验证器前也只能得到 `NOT_VERIFIED`，不得以
自报 producer、普通 trace 文件或唯一哈希将该门提升为 `PASS`。

证据模板见 `release-manifest.template.json`。最终 manifest 的每个叶子只允许 `PASS`、`FAIL` 或 `NOT_VERIFIED`；任一 `FAIL` 阻断发布，任一必需门 `NOT_VERIFIED` 禁止宣称生产上线通过。
