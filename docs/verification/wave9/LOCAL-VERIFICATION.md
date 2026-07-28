# W9 本地验证记录

## 结论

- 记录日期：2026-07-28（Asia/Shanghai）
- 基线：`0c09fc375b1d2973915234e50a9aac10900446ca`
- OpenSpec proposal commit：`f7a850cded8909c353affe40ff6eefeeab44c9df`
- W9 harness implementation commit：`5ba4e4642b8163a63bfe76de356bd2a649862029`
- W9 harness state：`PASS / READY_LOCAL_OFFLINE`
- 产品回归状态：`FAIL`（存在前端异步路由/分页用例失败或超时）
- Release verdict：`NOT_VERIFIED`
- 生产连接：0

`READY_LOCAL_OFFLINE` 只表示 W9 的证据、安全、性能、部署、备份恢复和浏览器矩阵工具合同可运行。它不覆盖 W7 FINAL integrated commit，也不抵消下述前端回归失败。

## 通过项

| 命令 | 结果 |
| --- | --- |
| `openspec validate wave9-release-hardening-and-acceptance --type change --strict --json` | 1/1 valid |
| `python3 -m unittest discover -s scripts/release/tests -v` | 37/37 pass |
| `python3 scripts/release/verify_wave9.py --repo . --run-id w9-local-20260728-01` | harness `PASS`; release `NOT_VERIFIED`; local/offline only |
| `python3 scripts/release/verify_threats.py ...` | static `PASS`; dynamic/release `NOT_VERIFIED` |
| `python3 scripts/release/native_preflight.py --repo .` | static `PASS`; 0 system changes; 0 network requests |
| `cd backend && ./mvnw test` | 177/177 pass（H2/test profile，不替代 MySQL 8.4） |
| `cd frontend && npm run lint` | pass |
| `cd frontend && npm run typecheck` | pass |
| `cd frontend && npm run build:all` | production/demo pass；存在既有大 chunk warning |
| `cd frontend && npm test -- --run src/shared/config/deployNginxContract.test.ts --maxWorkers=1` | 3/3 pass |
| 单独重跑失败的 attendance pagination case | 1/1 pass，说明聚合运行中的该次失败为时序/资源敏感 |

## 失败或未关闭项

1. `react-router-dom@7.18.1` 下 `src/app/App.test.tsx` 为 18/24 通过、6 个异步路由用例失败。A/B 恢复 `7.11.0` 后同命令仍只有 19/24 通过，故不能把问题唯一归因于安全升级；详见 [`npm-audit-applicability.md`](npm-audit-applicability.md)。
2. 前端考勤相关聚合命令为 78/79 通过，`AttendanceSetupPagination.test.tsx` 有 1 个 15 秒超时；该用例单独重跑 1/1 通过。聚合门仍按真实结果记为失败/不稳定，不能改写成全通过。
3. `npm audit` 保留 2 个 High 记录，均来自仅影响 unstable RSC API 的 `GHSA-qwww-vcr4-c8h2`；当前 SPA/library 源码机械检查为不适用，但动态安全总门仍是 `NOT_VERIFIED`。

## 未执行的外部验证

- W7 FINAL 同步、祖先关系和干净 integrated commit：`NOT_VERIFIED`
- 批准的 MySQL 8.4 LTS、36 个月数据、迁移与 50 并发：`NOT_VERIFIED`
- Chrome/Edge/iOS Safari/Android Chrome 七视口与权限/状态矩阵：`NOT_VERIFIED`
- 隔离 MySQL 8.4 的 PITR、业务一致性、RPO ≤15 分钟、RTO ≤4 小时人工演练：`NOT_VERIFIED`
- Ubuntu 24.04 的 `nginx -t`、`systemd-analyze verify`、健康检查、切流与回滚：`NOT_VERIFIED`

上述项没有在本 worktree 执行，也不得用本地 mock、H2、demo 或模板验证替代。最终执行时必须使用同一 integrated commit/run ID/environment ID，并保存 SHA-256 绑定的证据。
