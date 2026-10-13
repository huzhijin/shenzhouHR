## Context

W9 是考勤 P0-A 的横切发布波次，前置为 W7 FINAL，明确不依赖默认关闭的 W8。V1.9 要求在同一发布候选上完成安全、50 并发性能、36 个月容量、MySQL 8.4 LTS、原生 Nginx/systemd、RPO/RTO、真实浏览器和回滚验收；当前同步基线 `0c09fc375b1d2973915234e50a9aac10900446ca` 尚不包含 W7 FINAL，也没有可据此宣称生产就绪的集成提交或人工恢复证据。

仓库已有早期 Nginx/systemd 模板、本机 MySQL 安全脚本以及历史波次证据模式，但缺少统一 W9 release evidence contract。实现必须在本 worktree 内可独立测试，默认离线/计划模式，不连接生产，不把 H2、本机旧 MySQL、demo 页面或静态检查升级为外部运行通过。

## Goals / Non-Goals

**Goals:**

- 提供可执行、可单测、默认安全的威胁检查、HTTP 负载、原生部署预检和备份恢复演练工具。
- 把 W7 FINAL、integrated commit、运行环境、叶子证据和最终判定绑定到同一 run ID，并强制三态结论。
- 让当前 worktree 能完成静态合同、mock/sandbox 行为和失败路径验证，同时将外部门准确保留为 `NOT_VERIFIED`。
- 固定 V1.9 指标：50 并发列表 P95 ≤2 秒、看板 P95 ≤3 秒、详情 P95 ≤1 秒、50,000 行导出 ≤60 秒、36 个月容量 POC、RPO ≤15 分钟、RTO ≤4 小时。

**Non-Goals:**

- 不接生产环境、不切流、不安装系统服务、不读取真实凭据或真实员工数据。
- 不宣称 W7、MySQL 8.4、真实浏览器兼容、季度人工恢复或全系统上线已经通过。
- 不修改业务 API、业务规则、数据库迁移或 W8 薪资边界。
- 不以 Docker、H2、demo mode、静态页面或 mock 性能替代最终集成验收。

## Decisions

### 1. 统一三态 release evidence contract

每个 run 使用不可复用的 `run_id`，manifest 固定记录 schema version、源码 commit、dirty 状态、W7 FINAL 证据摘要、环境身份、开始/结束时间和所有叶子证据的路径与 SHA-256。叶子状态只允许 `PASS`、`FAIL`、`NOT_VERIFIED`：

- 任一真实失败为 `FAIL`；
- 没有失败但任一必需门未执行、来源不一致或依赖缺失为 `NOT_VERIFIED`；
- 只有全部必需门在同一 integrated commit/run 上为 `PASS`，且工作树干净、W7 FINAL 为 `PASS` 时才能得到 `PASS`。

W8 不出现在必需门列表。采用显式 JSON 合同和标准库校验器，而不是自由格式 Markdown 聚合，以便机械拒绝缺字段、伪造状态、路径逃逸和跨 run 拼接；Markdown 只作为人类可读摘要。

### 2. 验证工具默认离线并限制目标

所有会发出网络请求或调用数据库客户端的工具默认只输出 plan。执行模式要求显式 `--execute`、唯一 run ID 和证据目录；本轮实现只允许 loopback 或测试/恢复专用目标。脚本不得 source 凭据文件，不在命令行或日志输出 secret，并拒绝仓库内、符号链接、非当前用户所有或权限宽于 `0600` 的凭据文件。

这比提供通用生产连接开关更保守，但能保证 W9 worktree 的独立实现不会意外接入生产；真实预发布/生产流程应由基础设施负责人在后续受控环境中按同一合同执行。

### 3. 威胁验证采用“静态合同 + 授权动态矩阵”

静态检查立即验证 Nginx/systemd、开发入口、缓存、日志、secret 卫生和证据合同。动态矩阵生成 BOLA/IDOR、URL/对象替换、批量/导出越权、缓存恢复、会话撤销、敏感字段及限流用例，但只有在 W7 FINAL 集成环境、合成身份和明确授权下执行。工具必须把未执行矩阵标为 `NOT_VERIFIED`，不能因为静态检查通过而输出安全总门 `PASS`。

### 4. 性能 harness 使用无第三方运行依赖的 HTTP 驱动

标准库驱动读取版本化 JSON scenario，按固定并发和持续时间发送请求，输出样本数、成功率、状态码、P50/P95/P99、吞吐和阈值结论。测试使用本地 mock HTTP server，因此可验证调度、统计、阈值和证据格式；最终 50 并发及 36 个月数据结果必须来自同一 W7 integrated commit 和 MySQL 8.4 环境。

采用标准库而非引入 k6/JMeter，减少离线构建依赖；代价是最终高保真压测仍应由获批工具交叉验证，证据中记录 driver 与版本。

### 5. 原生部署保持不可变发布目录和最小权限

Nginx 负责 TLS、请求 ID、安全头、API 限流/大小限制、敏感响应 no-store、静态哈希资产长缓存和无查询串访问日志；systemd 使用无登录账号、只读文件系统、受限地址族、资源上限、健康相关启动约束和显式运行目录。发布预检只读校验 artifact checksum、权限、环境变量名、`nginx -t`、`systemd-analyze verify`、Flyway plan 和健康 URL，不自行复制文件或切流。

回滚仅切换到上一不可变应用/前端版本；数据库只允许前向迁移。若新迁移与旧应用不兼容，发布必须在切流前失败，而不是尝试逆向 SQL。

### 6. 备份恢复拆分为备份、校验和隔离恢复

全量备份使用一致性快照和显式 GTID/binlog 元数据；增量依赖 MySQL binlog/PITR。每个 artifact 生成 SHA-256、工具版本、数据库身份、起止位点和 UTC 时间。恢复工具只允许新建的 `_restore` 测试库并要求精确确认令牌，恢复后执行 Flyway、表/约束/业务清单/规则版本/月结快照/同步水位一致性检查。

自动化测试仅使用 fake client 和临时目录验证参数、secret、防误删、校验失败及状态传播；真实 MySQL 8.4 与人工 RPO/RTO 演练保持 `NOT_VERIFIED`。

### 7. 浏览器验收以矩阵为合同、截图为证据

矩阵固定浏览器族、六个核心视口及 4K、角色/权限、正常与 loading/empty/error/403/frozen、键盘/焦点、横向溢出、缓存恢复、敏感字段和 PAYROLL 零发现性。每一格记录 route、fixture/identity、预期、结果、截图/trace/hash 和执行环境；缺少真实浏览器、W7 FINAL API 或 MySQL 8.4 时该格只能是 `NOT_VERIFIED`。

## Risks / Trade-offs

- [当前基线没有 W7 FINAL] → 工具与合同可完成，所有依赖 W7 的动态结论保持 `NOT_VERIFIED`，同步上游后必须在新的 integrated commit 重跑。
- [mock 性能可能与真实系统偏差很大] → mock 结果只验证 harness，不进入发布指标；最终证据要求 MySQL 8.4、36 个月数据模型和环境指纹。
- [Nginx/systemd 版本差异] → 模板目标固定 Ubuntu 24.04 LTS，最终环境必须执行原生 `nginx -t` 与 `systemd-analyze verify`。
- [备份成功但不可恢复] → 发布门要求隔离恢复、内容一致性和人工计时，单独的 dump 成功不能满足恢复门。
- [证据可被跨 run 拼接] → manifest 对相对路径、内容 SHA、run ID、commit 和环境身份逐项校验，并拒绝工作树 dirty。
- [安全动态测试可能影响真实数据] → 只用合成身份和非生产目标，执行前需明确授权；本 worktree 不提供生产目标绕过开关。

## Migration Plan

1. 在本分支完成 OpenSpec、工具、模板和单元/静态合同测试，不连接外部环境。
2. W7 FINAL 到达后，以其最终 SHA 合入专用集成分支，生成新的 integrated commit；不得复用本分支的预集成结论。
3. 在批准的 MySQL 8.4 LTS 非生产环境执行迁移、威胁、容量、浏览器与隔离恢复，生成同一 run ID 的证据。
4. 基础设施负责人对候选 artifact 执行原生预检、备份、切流和回滚演练；所有门通过后才可提交上线评审。
5. 任一门失败或未验证时不发布，保留上一不可变版本和切流前数据快照；数据库仅以前向修复迁移处理。

## Open Questions

- W7 FINAL 的权威 marker 路径、签名方式和最终 SHA 待上游同步后冻结。
- 现网人数、日峰值打卡、月末并发、36 个月数据量与批准的压测网络档位尚未提供。
- MySQL 8.4 LTS 预发布实例、binlog/GTID 策略、备份存储/KMS、保留期和恢复演练人员尚未批准。
- 企业域名、TLS 证书、可信代理 CIDR、告警接收人和切流设施尚未提供。
- iOS Safari/Android Chrome 的批准设备/云真机与无障碍测试环境尚未提供。
