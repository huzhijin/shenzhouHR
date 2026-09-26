## 1. Release evidence contract

- [x] 1.1 定义 W9 release manifest、叶子 evidence 和严格 `PASS|FAIL|NOT_VERIFIED` 数据合同
- [x] 1.2 实现 manifest 生成/校验器，校验 run ID、commit、dirty、W7 marker、相对路径、SHA-256 和必需门
- [x] 1.3 为失败传播、缺失门、W8 非依赖、跨 run/commit、路径逃逸和摘要篡改添加自动化测试
- [x] 1.4 提供当前基线模板与摘要，明确总门 `NOT_VERIFIED` 及外部补证清单

## 2. Threat verification

- [x] 2.1 定义 P0-A BOLA/IDOR、枚举、范围、批量/导出、缓存、撤权、敏感日志、开发入口和响应头威胁矩阵
- [x] 2.2 实现离线静态威胁检查器，输出同一 evidence contract 的结构化叶子结果
- [x] 2.3 添加安全检查器正负向测试，证明未执行动态攻击测试时不得输出安全总门 `PASS`

## 3. Capacity and performance harness

- [x] 3.1 定义版本化 50 并发列表/看板/详情/导出场景及 36 个月容量元数据合同
- [x] 3.2 实现仅允许 loopback 执行的标准库 HTTP 负载驱动和 P50/P95/P99/吞吐/成功率判定
- [x] 3.3 使用本地 mock HTTP server 测试成功、超阈值、非 2xx、无样本和非 loopback 拒绝路径
- [x] 3.4 输出容量采集模板，并将缺少现网基线、MySQL 8.4 或 36 个月 POC 的结论固定为 `NOT_VERIFIED`

## 4. Native deployment hardening

- [x] 4.1 加固 Nginx 模板的 TLS、安全头、nonce、no-store、限流、请求大小、无 query 日志和静态资产缓存边界
- [x] 4.2 加固 systemd 单元的非 root、只读文件系统、地址族、系统调用/内核防护、资源上限和运行目录
- [x] 4.3 实现只读原生部署预检，校验 artifact、权限、环境变量名、开发入口和原生验证命令但不安装或切流
- [x] 4.4 添加 Nginx/systemd/预检合同测试并更新原生部署、切流、前向迁移和回滚说明

## 5. Backup and recovery

- [x] 5.1 实现默认 plan 的一致性全量备份脚本，记录 MySQL 身份、版本、GTID/binlog 位点、UTC 时间和 SHA-256
- [x] 5.2 实现仅允许 loopback `_restore` 沙箱和精确确认令牌的恢复脚本及恢复后验证钩子
- [x] 5.3 添加 fake MySQL client 测试，覆盖凭据文件权限/符号链接、目标 allowlist、确认令牌、checksum 和失败传播
- [x] 5.4 编写 RPO ≤15 分钟、RTO ≤4 小时人工恢复演练 runbook，并明确真实 MySQL 8.4 演练前为 `NOT_VERIFIED`

## 6. Browser release acceptance

- [x] 6.1 定义浏览器/OS、七视口、角色、关键状态、键盘、溢出、缓存/撤权和 PAYROLL 零发现性矩阵
- [x] 6.2 实现矩阵结构校验器，要求每格的 integrated commit、run ID、route、身份、环境和截图/trace SHA
- [x] 6.3 添加矩阵覆盖与 demo/缺证降级测试，并生成待 W7 FINAL 后执行的验收模板

## 7. Integrated verification and handoff

- [x] 7.1 实现 W9 本地总编排入口，运行静态/单元门并生成 harness-ready、release `NOT_VERIFIED` 摘要
- [x] 7.2 运行 OpenSpec 校验、W9 自动化测试、前端相关合同测试和后端回归，记录命令与真实结果
- [x] 7.3 更新 README/W9 验收索引，说明独立可验证范围、禁止生产连接和后续 integrated commit 重跑要求

## 8. External release gates

- [ ] 8.1 同步 W7 FINAL，创建干净的最终 integrated commit 并验证 W7 marker/祖先关系（当前 `NOT_VERIFIED`）
- [ ] 8.2 在批准的 MySQL 8.4 LTS 与 36 个月容量数据上完成迁移、50 并发性能和容量 POC（当前 `NOT_VERIFIED`）
- [ ] 8.3 在批准的真实 Chrome/Edge/iOS Safari/Android Chrome 环境完成七视口与权限/状态矩阵（当前 `NOT_VERIFIED`）
- [ ] 8.4 在隔离 MySQL 8.4 环境由人工完成 PITR、业务一致性、RPO/RTO 和回滚演练（当前 `NOT_VERIFIED`）
- [ ] 8.5 在 Ubuntu 24.04 LTS 候选环境执行 `nginx -t`、`systemd-analyze verify`、健康检查、切流/回滚和最终上线评审（当前 `NOT_VERIFIED`）
