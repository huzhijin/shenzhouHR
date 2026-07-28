## Why

神州 HR 考勤 P0-A 在 W7 FINAL 之后仍需要一个独立、可审计且默认拒绝误判的发布门，才能把安全、容量、原生部署、备份恢复和浏览器验收结论绑定到同一份集成源码与运行环境。当前基线只提供分散的早期部署模板和历史波次证据，尚不能证明 MySQL 8.4 LTS、RPO/RTO、真实多视口或全系统上线已通过。

## What Changes

- 建立 W9 发布前威胁测试合同，覆盖 BOLA/IDOR、对象枚举、批量/导出越权、缓存泄漏、敏感日志、开发入口和安全响应头，Critical/High 未关闭时拒绝发布。
- 建立可重复的容量/性能 harness，固定合成数据、50 并发、36 个月数据量和查询/看板/详情/导出等 V1.9 指标，并明确环境不足时输出 `NOT_VERIFIED`。
- 加固 Ubuntu 24.04 LTS 原生 Nginx/systemd 部署模板、离线预检、切流/回滚边界和 secret/dev-profile 防护；不连接或修改生产环境。
- 提供 MySQL 备份、增量日志/PITR 恢复演练脚本与静态/沙箱测试，保留 RPO ≤15 分钟、RTO ≤4 小时和人工季度演练为真实发布门。
- 建立 Chrome/Edge/iOS Safari/Android Chrome 与规定视口、角色、状态、键盘、缓存和敏感数据检查的浏览器验收矩阵框架。
- 建立 release evidence contract：证据必须来自同一 integrated commit/run ID，逐项记录 `PASS`、`FAIL` 或 `NOT_VERIFIED`，W7 FINAL 缺失、MySQL 8.4 未核验或人工恢复未完成时禁止宣称可生产上线。

## Capabilities

### New Capabilities

- `release-threat-verification`: 考勤 P0-A 发布前威胁模型、自动化攻击面检查和安全发布阻断规则。
- `capacity-performance-verification`: 合成容量模型、可重复负载场景、指标阈值和可比较证据格式。
- `native-deployment-hardening`: Nginx/systemd 原生部署合同、离线预检、最小权限、切流和回滚边界。
- `backup-recovery-verification`: 全量备份、增量日志/PITR、恢复演练、安全处理和 RPO/RTO 证据。
- `browser-release-acceptance`: 多浏览器、多视口、多角色和关键状态的上线验收矩阵及证据要求。
- `release-evidence-contract`: W7 依赖、同源 provenance、三态结论和最终发布判定合同。

### Modified Capabilities

无。W9 新增横切发布门，不改变当前基线中既有业务能力的需求语义。

## Impact

- 新增 `scripts/release/` 下的离线验证、性能、备份恢复和证据校验工具及其自动化测试。
- 加固 `deploy/nginx/`、`deploy/systemd/`，新增 `deploy/backup/` 与原生发布说明；不使用 Docker、Kubernetes、Redis 或消息队列。
- 新增 `docs/verification/wave9/` 的验收矩阵、证据清单和明确的外部验证登记。
- 不新增业务 API 或数据库迁移，不依赖 W8，不接生产环境；W7 FINAL、最终 integrated commit、MySQL 8.4 LTS、真实浏览器与人工恢复演练由后续集成环境补证。
