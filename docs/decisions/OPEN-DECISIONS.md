# 神州HR 待决策与外部条件登记

> 本文件追加式维护。关闭条目时仅将标题改为 `RESOLVED` 并增加 `Resolution`，不得删除历史。

## OPEN — waiting-on-external-condition — MySQL 验证与外部系统联调条件
- **Date**: 2026-07-20
- **Source**: PRD V1.7 / ADR-005 / 首阶段实施计划
- **Open item**: 真实 MySQL 8.4 测试实例、致远/得力联调环境、网络白名单、脱敏样本和凭据尚未提供；`SHENZHOUHR_DB_URL`、`SHENZHOUHR_DB_USERNAME`、`SHENZHOUHR_DB_PASSWORD`、`SEEYON_DB_URL`、`SEEYON_DB_USERNAME`、`SEEYON_DB_PASSWORD`、`DELI_APP_KEY`、`DELI_APP_SECRET` 当前状态均为 missing。
- **Related constraints**: 致远自建表只读且通过 `OAQueryMappingProfile`；得力禁用破坏性接口；仓库不得保存凭据值。
- **Current leaning**: 首阶段只实现端口、合成契约夹具和显式开发适配器。
- **Blocked by**: 客户提供批准的 MySQL 8.4 与外部系统测试环境、最小权限账号、白名单和脱敏数据。
- **Resolves when**: Flyway 迁移/回滚与唯一约束测试、只读权限测试、字段映射签字、得力官方契约联调和凭据轮换流程全部通过。
- **Update 2026-07-26**: 已获授权使用仓库外 `wave2-runtime.env`，MySQL 客户端、Flyway、迁移账号和两类应用账号变量均为 available；WAVE-3 runId `w3-20260726-1917` 在迁移前确认目标服务不是 MySQL 8.4 LTS并 fail-closed，故 V6→V7、权限和执行计划仍未验证。
- **Update 2026-07-29**: 得力只读联调凭据已保存在本机私有、Git 忽略且权限为 `0600` 的 `.env.deli.local`，当前变量名为 `DELI_EPLUS_APP_KEY` / `DELI_EPLUS_APP_SECRET`；已验证 HTTPS、签名及 `CHECKIN/checkin_query` 返回业务码 `0`，当前查询窗口为零行。得力仍待租户初始化游标与人员绑定确认；致远 OA MySQL 数据源、审批状态及明细外键契约仍未提供。
- **Update 2026-07-29（V9 checksum 变更）**: 隔离 MySQL 8.4.10 首次真实空库迁移证明 V9 的未引用 `row_number` 标识符会因 `ROW_NUMBER` 保留字在建表时返回语法错误；该次运行停在 V9，V11 未执行。V9 仅对该列定义、两个索引和 CHECK 中的同一标识符增加反引号，并同步精确源码校验合同。任何已经记录 V9 Flyway history 的外部数据库都不得静默执行 `repair`；必须先核对实际表结构、原 checksum 与当前 checksum，并通过受控审批后再决定修复策略。
- **Update 2026-07-30（隔离库 live7）**: 修正 V9 后，隔离 MySQL 8.4.10 已完成 V11 live7 验证：29 张边界表、28 张关系表、29 条精确外键、44/44 已启用 CHECK 和 5 组索引切换均通过；权限 A+B 的角色/能力范围与当前身份/公司一致性组合、以及跨公司考勤组分配拒绝均由本地自动化覆盖。该结论不关闭本条外部门禁：生产容量/50 并发、连续 binlog/PITR、真实 OA MySQL、真实得力租户、真实浏览器/设备矩阵和 Ubuntu 部署/回滚仍为 `NOT_VERIFIED`。

## RESOLVED — design-decision-to-evaluate — 统一身份认证与账号生命周期
- **Date**: 2026-07-20
- **Source**: 安全设计 / 首阶段权限基础设施
- **Open item**: 客户现有 SSO 协议、身份提供方、账号启停来源、会话和近期登录策略尚未确认。
- **Related constraints**: P0 不强制 MFA；工资揭示和高风险动作要求近期登录；服务端默认拒绝并保留 OIDC/WebAuthn 扩展点。
- **Current leaning**: 先实现可替换的 `PrincipalResolver` 与本地合成开发身份，不实现生产登录。
- **Blocked by**: 客户 IAM/AD/SSO 资料和安全负责人确认。
- **Resolves when**: 认证协议、属性映射、撤权传播和会话策略完成 ADR 与集成验收。
- **Resolution**: 2026-07-24 由 V1.9 明确裁决为神州 HR 本地独立账号密码与服务端 session，不依赖致远 OA SSO。默认覆盖首次改密、失败锁定、重置、停用/离职/改密撤销 session、退出、权限和审计；具体实现仍须通过 V1.9 波次 1 的安全验收。

## OPEN — waiting-on-external-condition — 得力坐标系与地图服务
- **Date**: 2026-07-20
- **Source**: ADR-006 / 地图位置需求
- **Open item**: 得力返回坐标的坐标系和生产地图服务供应商尚未得到可验证确认。
- **Related constraints**: 必须保留原始坐标、校验和转换状态；未知坐标不得静默转换或绘点。
- **Current leaning**: `coordinate_system=UNKNOWN` 且 `map_point_available=false`。
- **Blocked by**: 得力书面契约/联调样本和客户批准的地图服务。
- **Resolves when**: 坐标样本交叉验证、转换误差门和字段权限测试通过。

## OPEN — waiting-on-external-condition — 薪酬法定政策与期初累计
- **Date**: 2026-07-20
- **Source**: PRD V1.7 / ADR-002 / 工资实施阶段
- **Open item**: 社保、公积金、个税年度政策、适用地区、舍入规则、工资项目口径和上线年度期初累计尚未由薪酬负责人签字。
- **Related constraints**: 所有政策必须版本化并冻结进工资输入；缺失或冲突时工资发布失败关闭。
- **Current leaning**: 先实现政策版本模型和校验框架，不预置未经确认的业务数值。
- **Blocked by**: 客户薪酬/财务提供正式制度、法定参数和控制数。
- **Resolves when**: 规则表签字、边界用例和连续月份控制数验收通过。

## OPEN — waiting-on-external-condition — 生产资产与文件存储方案
- **Date**: 2026-07-20
- **Source**: 品牌规范 / 部署架构 / 阶段 0
- **Open item**: 经企业授权的 Logo/favicon 原文件、生产加密文件存储产品、保留锁和备份落点尚未提供。
- **Related constraints**: Logo 必须本地版本化且不变形；工资附件和导出需加密、最小权限、保留与审计；不得使用设计阶段临时资源上线。
- **Current leaning**: 代码只定义 `BrandConfig` 与 `FileObjectStore` 端口，开发环境不放入真实资产或敏感文件。
- **Blocked by**: 企业品牌授权文件和基础设施/安全负责人选型。
- **Resolves when**: 资产授权、存储加密/保留/恢复测试和生产配置审查完成。

## OPEN — waiting-on-external-condition — UmaDev 全量治理的既有配置基线识别
- **Date**: 2026-07-23
- **Source**: 神州 HR V1.9 治理问题收口
- **Open item**: 当前工作区缺少可用 Git 基线，`umadev ci` 进入 filesystem fallback 后把既有 `.umadevrc` 视为敏感文件写入；完整报告中产品与源码文件已无命中，仅该工具配置自触发 `UD-SEC-001`。
- **Related constraints**: 不删除项目配置，不关闭或绕过不可逆安全地板；路径排除按工具规则不能覆盖安全地板。
- **Current leaning**: 在具有可信 Git 基线的 CI 工作区重跑全量门禁，或由 UmaDev 提供区分既有配置与本次写入的受支持基线机制。
- **Blocked by**: 当前工作区无可用于差异判定的 Git 元数据，CLI 也没有既有敏感配置基线参数。
- **Resolves when**: Git-backed CI 能确认 `.umadevrc` 未在本次变更中写入且 `umadev ci` 全量通过，或工具修复该 filesystem fallback 自拦截。

## OPEN — waiting-on-external-condition — Nginx 生产配置语法复验
- **Date**: 2026-07-23
- **Source**: 「按建议的去修复」生产响应头与 CSP nonce 修复
- **Open item**: `deploy/nginx/shenzhouhr.conf` 已由自动化合同测试验证响应头继承、缓存策略和 nonce 链路；当前环境没有 Nginx 可执行文件，尚不能运行 `nginx -t`。
- **Related constraints**: 生产配置依赖 TLS 证书路径、静态文件目录和 Nginx `http_sub_module`；不得把缺少真实运行时的静态检查表述为语法复验已通过。
- **Current leaning**: 在与生产一致的 Ubuntu 24.04/Nginx 环境中，使用测试证书和构建产物执行 `nginx -t` 后再加载配置。
- **Blocked by**: 当前工作区没有 Nginx 运行时及对应的 TLS/静态资源路径。
- **Resolves when**: 生产同版 Nginx 的 `nginx -t` 通过，加载配置后抽样响应同时满足 CSP nonce、全量安全响应头及 API/静态资源缓存合同。

## OPEN — existing-design-boundary — UmaDev 评审摘要与超时的上游项目配置
- **Date**: 2026-07-23
- **Source**: 重复出现的 frontend/backend/QA/security review unavailable
- **Open item**: UmaDev 1.0.64 的代码摘要按操作系统 `read_dir` 顺序截取，且没有项目级 source-focus 配置；`UMADEV_REVIEW_TURN_TIMEOUT_SECS` 也只能从启动进程环境读取。本次仓库已将原型交付物归入 `output`、增加首屏跨层合同测试与标准黑板文档，并提供 `scripts/run_umadev`，但裸 `umadev` 的未来新进程仍受其启动环境影响。
- **Related constraints**: 不降低全项目 Codex 推理质量，不篡改全局 npm 安装；评审必须只依据真实文件和可执行合同，不复制或伪造业务实现。
- **Current leaning**: 项目内统一通过 `scripts/run_umadev` 启动；等待上游提供稳定排序/按席位取样和项目级评审超时配置。
- **Blocked by**: UmaDev 1.0.64 尚无对应项目配置面。
- **Resolves when**: 上游版本支持确定性的跨前后端摘要与仓库内 review timeout 配置，升级后移除项目启动包装并保持 `design/contracts/review_input_contract_test.py` 全绿。

## OPEN — waiting-on-external-condition — 异构考勤机样本与旧格式范围
- **Date**: 2026-07-24
- **Source**: V1.9 离线/异构考勤打卡 Excel 导入重基线
- **Open item**: 至少一份真实且脱敏的非得力厂商/型号 `.xlsx` 导出样本尚未提供；旧 `.xls` 和 CSV 是否作为首期原生输入尚未裁决。
- **Related constraints**: 标准 `.xlsx`、字段映射、原始行保留和服务端预检必须先实现；不得用姓名/部门唯一匹配，不得导入计算结果。
- **Current leaning**: 首期完整支持标准 `.xlsx` 和可版本化厂商/型号/地点 mapping profile；`.xls`/CSV 等样本到位后再决定。
- **Blocked by**: 客户提供经批准的脱敏设备导出样本、字段含义和设备时区/方向说明。
- **Resolves when**: 至少一个异构型号完成 mapping、50,000 行、重复、时区和错误报告验收，并对 `.xls`/CSV 形成书面范围决策。

## OPEN — waiting-on-external-condition — Open Design V1.9 浏览器像素验收
- **Date**: 2026-07-24
- **Source**: Open Design V1.9 接入重基线
- **Open item**: 导出包当前声明 `VISUAL_CAPTURE_NOT_AVAILABLE`；真实 React 页面尚未在规定桌面、平板、手机和 4K 视口完成截图、焦点、键盘、对比度与溢出验收。
- **Related constraints**: 静态 HTML/JS 不能整体复制进 React；必须使用真实开发 API 状态和合成数据，且不得出现薪资前台或原型控制条。
- **Current leaning**: 在波次 7 的 `preview_confirm` 统一执行多断点浏览器验收。
- **Blocked by**: 对应 React 纵向切片和真实开发 API 尚未获授权实现。
- **Resolves when**: `responsive-screenshot-checklist` 规定视口及 3840×2160 大屏全部通过，无横向溢出、裁切、焦点丢失、低对比度、原型假按钮或敏感数据。

## OPEN — waiting-on-external-condition — WAVE-1 本机 MySQL 管理凭据注入
- **Date**: 2026-07-24
- **Source**: V1.9 docs_confirm WAVE-1 实施目标
- **Open item**: 本机 MySQL 服务可达，但当前执行上下文中 `SHENZHOUHR_MYSQL_ROOT_PASSWORD` 状态为 missing；未读取 shell 历史或其他非授权秘密来源，无密码 root 认证已失败。
- **Related constraints**: 凭据只允许执行期临时使用；不得进入仓库、命令参数、日志、报告或浏览器；只允许操作 `shenzhou_hr_dev` 与 `shenzhou_hr_test`。
- **Current leaning**: 先完成契约、迁移、应用和无凭据静态验证；凭据经仓库外 `0600` 环境文件注入后，执行受控建库、账号授权、迁移和真实联调。
- **Blocked by**: `SHENZHOUHR_MYSQL_ROOT_PASSWORD` 在当前执行环境中不可用。
- **Resolves when**: 该变量通过仓库外 `0600` 环境文件变为 available，且两库建库、最小权限、Flyway、真实 API/MySQL 和浏览器联调全部通过。

## OPEN — waiting-on-external-condition — Trivy 漏洞数据库首次同步
- **Date**: 2026-07-24
- **Source**: V1.9 WAVE-1 安全审计
- **Open item**: Trivy 0.69.3 的 secret/misconfiguration 扫描已完成且无发现，但 vulnerability scanner 的首次 OCI 漏洞库同步连续等待后仍未完成；`TRIVY_DB_REPOSITORY` 使用默认镜像，专用镜像或缓存状态为 missing。
- **Related constraints**: 不得把未完成的漏洞库同步表述为全量 SCA 已完成；不得使用 Docker；不得把外部服务失败等同于源码安全。
- **Current leaning**: 当前以 `npm audit`、Maven 精确依赖树、Spring/React/Vite 官方安全公告和 Semgrep/Gitleaks/Trivy secret/misconfiguration 结果组成有边界的安全证据；在可用的 Trivy DB 镜像或缓存环境补跑 vulnerability scanner。
- **Blocked by**: 默认 OCI 漏洞库镜像在当前执行窗口内无法完成首次同步。
- **Resolves when**: Trivy vulnerability DB 同步成功，并对 `backend/pom.xml` 与 `frontend/package-lock.json` 完成扫描、审阅和报告。

## OPEN — waiting-on-external-condition — WAVE-2 本机 MySQL 管理凭据注入
- **Date**: 2026-07-25
- **Source**: V1.9 WAVE-2 组织与员工期初导入实施目标
- **Open item**: `SHENZHOUHR_MYSQL_ROOT_PASSWORD` 状态为 missing；`SHENZHOUHR_FLYWAY_PASSWORD`、`SHENZHOUHR_DEV_DB_PASSWORD` 状态为 available，但本机 MySQL 对两者均返回 1045，未取得可用管理或应用连接。
- **Related constraints**: 凭据只允许执行期临时使用；不得进入仓库、命令参数、日志、报告或浏览器；只允许操作 `shenzhou_hr_dev` 与 `shenzhou_hr_test`，且开发库只执行前向迁移。
- **Current leaning**: 不再重复尝试失效账号；取得当前 root 凭据后，用仓库外 `0600` 临时配置校正最小权限账号，再执行受控验证并在退出时删除临时配置。
- **Blocked by**: 当前 root 凭据不可用，现有迁移账号与 dev 应用账号的认证信息已失效或与本机实例不一致。
- **Resolves when**: `SHENZHOUHR_MYSQL_ROOT_PASSWORD` 状态为 available，且空测试库迁移、V1～V4升级、validate、no-op、checksum、应用账号权限和真实 API/MySQL 验收全部通过。
## OPEN — waiting-on-external-condition — WAVE-2 应用内浏览器后端暂不可用
- **Date**: 2026-07-25
- **Source**: WAVE-2 真实 API 浏览器验收
- **Open item**: 当前 Browser runtime 的可用浏览器列表为空，无法执行生产 React 路由的受控浏览器交互与截图。
- **Related constraints**: 必须使用真实 8080 API 与普通 5173 dev 服务；不得用 demo 或其他浏览器自动化栈替代真实浏览器证据。
- **Current leaning**: 后端与前端服务就绪后重试同一 Browser runtime；若仍为空，将浏览器截图与交互验收保留为 NOT_VERIFIED。
- **Blocked by**: 应用内浏览器后端不可用。
- **Resolves when**: Browser runtime 至少暴露一个可选择的浏览器，并完成真实 API 的桌面、平板和 390×844 验收。

## OPEN — existing-design-boundary — 前端共享运行时 chunk 体积预算
- **Date**: 2026-07-26
- **Source**: WAVE-3 前端生产与 demo 构建验证
- **Open item**: 两类构建均成功且业务页面已按路由懒加载，但共享 `jsx-runtime` 压缩后约 574 kB，超过 Vite 默认 500 kB 提示线；当前没有真实 RUM、弱网或缓存命中数据用于裁定拆包收益。
- **Related constraints**: 不得为消除非失败警告盲目拆分 React/Ant Design 共享依赖并造成重复下载或缓存退化；最终上线验收仍需给出可测量的首屏预算。
- **Current leaning**: 在最终性能验收中以真实生产构建、brotli/gzip 传输体积和六视口加载轨迹建立基线，再按依赖图选择稳定的 vendor manual chunk。
- **Blocked by**: 缺少真实浏览器性能轨迹、生产 CDN 压缩配置与目标网络档位。
- **Resolves when**: 真实生产环境的关键路由加载预算获批，冷/热缓存轨迹达标，或基于依赖图完成拆包且无回归。

## OPEN — waiting-on-external-condition — WAVE-3 六视口应用内浏览器验收
- **Date**: 2026-07-26
- **Source**: WAVE-3 任务 6.5 运行时与浏览器验收
- **Open item**: 应用内 Browser 的必需控制工具当前未暴露，Agent Browser 技能对应 CLI 也未安装；无法对真实 MySQL 8.4 普通模式执行六视口截图、键盘、焦点、溢出、角色和错误态交互验收。
- **Related constraints**: 不得安装未授权全局工具，不得用 demo 或 H2 截图替代真实 MySQL 8.4 普通模式证据；可将实际 HTTP/H2 socket 测试和静态响应式测试作为补充证据，但结论必须保持 `NOT_VERIFIED`。
- **Current leaning**: Browser runtime 恢复后连接真实 8080 API 与普通 5173 dev 服务，按 390×844、768×1024、1024×768、1366×768、1440×900、1920×1080 逐一验收。
- **Blocked by**: 应用内 Browser 控制工具不可用、MySQL 8.4 与有效运行账号不可用。
- **Resolves when**: 同一 runId 下六视口、权限变体、失败态、键盘与 demo 零业务 API 证据全部通过。
- **Update 2026-07-26**: 已通过 `npx` 临时 CLI 复用系统 Chrome，在 runId `w3-20260726-1917` 完成 demo 六视口、四个 W3 直达页、真实客户端 404 与零业务 API/零 WebSocket检查；普通模式 HR_ADMIN/AUDITOR、403/empty/error、键盘/axe 仍因 MySQL 8.4 后端和真实账号会话缺失而未验证。
## OPEN — design-decision-to-evaluate — 策略绑定停用的 append-only 表达
- **Date**: 2026-07-27
- **Source**: W3 scoped-policy lifecycle/binding 最终模型实现
- **Open item**: `attendance_policy_binding_family/revision` 当前没有 lifecycle/tombstone fact；既有 deactivate API 只能追加与现有内容相同的 revision，无法在不伪造 mutable status 的情况下表达“此后无有效 binding”。
- **Related constraints**: binding family 唯一 `(group identity,kind)`；revision immutable；无 status/priority winner；解析必须 exact-one 且历史可重放。
- **Current leaning**: 为 binding 增加独立 append-only lifecycle fact，并在 business/knowledge time 派生 ACTIVE/DEACTIVATED。
- **Blocked by**: active OpenSpec registry 尚未声明 binding lifecycle 表及其 retained framing。
- **Resolves when**: OpenSpec/registry/V7/H2/Mapper/OpenAPI 同时加入并验证 binding lifecycle fact，deactivate 并发一胜一且历史解析测试通过。

## OPEN — deployment-safety-decision — 外部数据库 V9 checksum 兼容处置
- **Date**: 2026-07-29
- **Source**: 隔离 MySQL 8.4.10 真实 V1→V10 迁移
- **Open item**: V9 原文件未引用 MySQL 保留标识符 `row_number`，真实执行返回 SQL 1064；本仓已仅增加反引号并有意把 V9 SHA-256 从 `7855a44e2f6d066490db4b2259bf33903f295a7fe85232af3dac0c21d46ed3cd` 更新为 `56a0476ef24a6fd6ae8559f11b8ace4fdec274f6e1ce681c5525e8335dbd9150`。尚不清楚是否存在已登记成功 V9 history 的外部数据库。
- **Related constraints**: 禁止为消除 checksum mismatch 静默执行 Flyway `repair`；不得假定外部库与本机失败现场相同，也不得覆盖实际已部署 schema。
- **Current leaning**: 新库和确认未成功执行 V9 的库使用修正后的 migration；发现成功 V9 history 时先冻结发布，导出实际 schema/checksum、完成备份与差异评审，再审批前向兼容处置。
- **Blocked by**: 外部开发、测试、预生产和生产数据库的 Flyway V9 history/checksum 与 `punch_import_row` 实际定义尚未核对。
- **Resolves when**: 所有目标环境完成只读盘点；不存在成功旧 V9，或已为每个存在旧 V9 的环境批准并演练不依赖静默 repair 的前向迁移方案。

## OPEN — waiting-on-external-condition — UmaDev 文件级词法误报的精确裁决机制
- **Date**: 2026-07-27
- **Source**: 用户要求收口 25 条 UmaDev governance 命中
- **Open item**: `umadev ci` 将传播层的 `file` 字段、Spring `@Profile`、DOM `anchor.remove()`、禁止 DELETE/明文密码的负向测试，以及已通过 `PasswordCodec` 编码的 hash 持久化接口按整文件判为漏洞；当前报告不提供触发行、AST 构造或 rule+realpath 精确裁决。
- **Related constraints**: 不得使用 exclusions/disabled、拆字符串、无业务意义改名、搬文件或删除负向安全测试；真实上传验证、可访问性、密码编码和不可恢复删除边界必须保持。
- **Current leaning**: 由 UmaDev 将规则升级为语言感知/数据流判定，并支持带审计证据的 rule+realpath+construct 精确裁决。
- **Blocked by**: 当前 CLI 仅返回文件级结果，项目没有合法的精确 waiver 或基线机制；修改业务源码只能制造扫描器导向的语义污染。
- **Resolves when**: 上游规则能区分上传入口与字段传播、DOM/集合删除与数据删除、密码 hash 与明文密码、负向合同字符串与执行路径，且本仓 `umadev ci` 在零 exclusions/disabled 下通过。

## OPEN — waiting-on-external-condition — GitHub 推送因 pack 体积失败
- **Date**: 2026-08-07
- **Source**: Task A 收尾（用户终端截图）
- **Open item**: `git push` 在写入 9.09 MiB 时返回 `RPC failed; HTTP 400 curl 22` 与 `send-pack: unexpected disconnect while reading sideband packet`；本地 commit 与 `main` fast-forward 合并均已成功，但 `origin/main` 未更新，当前 41 个 commit 未推送。末尾 `Everything up-to-date` 为误导性输出，不代表推送成功。
- **Related constraints**: 推送是对外动作，需用户点头；不得使用强制推送或历史改写来绕过体积问题。
- **Current leaning**: 提高 `http.postBuffer`，或改用 SSH 传输避开 HTTP 缓冲上限；若仍失败则分批推送。仓库含大体积构建产物历史（`release/` 直到 `df3b7b5` 才被忽略），是 pack 偏大的可能原因，待核实。
- **Blocked by**: 用户明确表示本轮先不处理推送。
- **Resolves when**: `origin/main` 与本地 HEAD 一致，且 `git status` 显示无未推送 commit。

## OPEN — waiting-on-external-condition — OA 加班类别枚举与主从关联列
- **Date**: 2026-08-07
- **Source**: Task B / B-5 加班认定；`docs/contracts/oa-attendance-form-mapping-signoff-matrix.md`
- **Open item**: 加班认定需要的两类事实未确认：(1) `formson_0172.field0096` 加班类别的原始值到"义务加班/加班费/调休"三选项的封闭映射（`OA-ENUM` 类，枚举未确认）；(2) `formmain_0171` 与 `formson_0172` 的真实主从关联列（`OA-FK-01`，候选列名 `formmain_id` 明确不得使用）。同类缺口还有 `OA-STATUS-01/02` 审批状态列名与"最终批准"取值、LEAVE `field0089` 请假类别枚举。
- **Related constraints**: 签字矩阵第 13 行要求 `NOT_VERIFIED` 项必须阻断有效考勤证据，不得使用猜测值；OA 侧一律只读；本地 fixture 与 H2 测试不能把状态改为 `VERIFIED`。表与字段目录已在仓库内记录，无需用户重新提供。
- **Current leaning**: B-5 加班认定按"单据授权 × 打卡证据"双侧结构先建骨架，枚举映射与关联列以配置化映射表注入，确认前对未知值 fail-closed 而非猜测归类。
- **Blocked by**: 缺少批准环境的脱敏样本：`field0096` / `field0089` 的全量 distinct 值、审批状态全量 distinct 值、主从表 0/1/N 基数证明。
- **Resolves when**: 上述 distinct 值与关联列由 OA 流程管理员签字确认，签字矩阵对应行从 `NOT_VERIFIED` 转为 `VERIFIED`。

## OPEN — design-decision-to-evaluate — 得力凭据从进程级下移到按源存储
- **Date**: 2026-08-07
- **Source**: Task B / B-2 多台考勤机前台配置
- **Open item**: 现状 `DeliEplusProperties`（`shenzhouhr.integrations.deli-eplus`）为进程级单套凭据，经 `application.yml` 环境变量注入，变量名 `DELI_EPLUS_APP_KEY` / `DELI_EPLUS_APP_SECRET`。需求为多台考勤机各自独立 key 且管理员可在前台配置，需将凭据下移到按 `attendance_source_id` 存储。静态加密方式（应用层信封加密 vs MySQL 侧）与主密钥托管位置未定。
- **Related constraints**: 凭据值不得入仓库、日志、API 响应；`deli_source_operation`（V27）、`deli_employee_binding_revision` 与 `deli_source_connection_probe`（V28）已按 `attendance_source_id` 建模，可复用；配置入口仅管理员可见。
- **Current leaning**: 应用层信封加密，密文入库、主密钥经环境变量注入；响应只回凭据引用名与 available/missing 状态，绝不回显密文或明文。
- **Blocked by**: 主密钥托管位置需与部署方式（宝塔面板 MySQL 8.0.45）一并确认；进程级配置在迁移期的兼容处置未定。
- **Resolves when**: 按源凭据可增删改查、探测按源独立返回结果，且凭据值在响应与日志中均不可见的验证通过。

## OPEN — design-decision-to-evaluate — 出勤率公式方向与用户口述口径冲突
- **Date**: 2026-08-08
- **Source**: 用户 2026-08-08 口述答复 C；`docs/contracts/2026-08-06-reporting-business-confirmation.md` R01/R02
- **Open item**: 用户两次明确表述出勤率为「应出勤天数 ÷ 实际出勤天数」，对应 R01 选项 `C`（保留 V30 文本）。该方向在数学上会随缺勤增加而升高并可超过 100%：满勤时 = 1，缺勤一天时 > 1。R01 已就此列为不可同时成立的三份口径之一，并建议选项 `A`。用户第二句「带薪的假期算出勤」独立成立且清晰，对应 R02 选项 `B`。
- **Related constraints**: V30 必须按既有数据库 checksum 原字节保留，只能通过 V32 前向修正，不得改写；`ATTENDANCE_RATE_CONFIRMED_OVER_SCHEDULED_V1_PROVISIONAL` 为当前 Java 常量，方向与用户口述相反；报表口径变更必须带 `formulaVersion`，历史投影不可静默改写。
- **Current leaning**: 判断为口述时的分子分母顺序倒置，真实意图应为 `实际出勤 ÷ 应出勤`（即 R01 选项 `B`，或按分钟计的 `A`）。未取得书面确认前不改动现有常量与实现，不按字面实现会产出 >100% 的比率。
- **Blocked by**: 需用户书面确认分子分母方向：`实际 ÷ 应该`（建议）还是字面的 `应该 ÷ 实际`；以及按天还是按分钟计。
- **Resolves when**: 方向与粒度书面签字，`formulaVersion` 落定，并在 V32 前向迁移中体现，边界用例（满勤 / 半天缺勤 / 全月请假 / 零应出勤）全部通过。

## RESOLVED — waiting-on-external-condition — 得力只读联调凭据
- **Date**: 2026-08-08
- **Source**: 用户 2026-08-08 提供得力 E+ key/secret 及接口文档地址
- **Resolution**: 用户提供的 key/secret 与仓库外既有 `.env.deli.local`（`0600`、Git 忽略）中已保存的值逐字节一致，无需改动。接口文档为 `http://doc.delicloud.com/v3/integration/oa.html`。凭据值不入仓库、日志与响应的约束继续有效。本条仅关闭「凭据是否可用」，得力租户初始化游标与人员绑定确认仍在上文 2026-07-29 条目中保持 OPEN。

## OPEN — waiting-on-external-condition — OA 只读凭据已提供但本机网络不可达
- **Date**: 2026-08-08
- **Source**: 用户 2026-08-08 提供 OA 只读数据源
- **Open item**: 用户提供 `192.168.2.169:3308`、库 `szoa`、账号 `kaoqin2026`，已写入仓库外 `0600` 的 `.env.oa.local` 与 `~/.shenzhouhr-oa-readonly.cnf`（均 Git 忽略，值不入仓库）。本机 MySQL 客户端握手在读取初始通信包阶段即失败（`ERROR 2013`，SSL 关闭后同样失败）。`nc -z` 探测不可用于判定连通：对同主机确定关闭的 3999 端口同样返回 OPEN，属假阳性，不得作为可达证据。
- **Related constraints**: OA 一律只读；未确认项必须阻断有效考勤证据，不得使用猜测值。
- **Current leaning**: 待在能实际路由到该内网段的环境中执行只读查询。
- **Blocked by**: 当前执行环境到 `192.168.2.169:3308` 无实际 MySQL 层可达性。
- **Resolves when**: 同一凭据在可达环境完成握手，并取得下条所需的三类 distinct 值与基数证明。

## OPEN — waiting-on-external-condition — OA 枚举/审批/绑定的用户口述方法待实库验证
- **Date**: 2026-08-08
- **Source**: 用户 2026-08-08 口述 OA 查询方法
- **Open item**: 用户给出三条方法，已记入 `docs/contracts/oa-attendance-form-mapping-signoff-matrix.md` 第 9 节，状态保持 `NOT_VERIFIED`（口述不构成实库证据）：(1) 枚举经 `ctp_enum_item` 按枚举 id 查 `showvalue` 取中文，加班类别为「义务加班 / 加班费 / 调休」三选项；(2) 审批状态经 `formmain_xxxx.id = col_summary.form_recordid` 关联 `col_summary.state`，`3` = 结束（有效）、`0` = 发起中、`2` = 撤销、`NULL` = 保存待发；(3) 工号绑定为 `org_member.code` ↔ 得力 `employee_number`，OA 表单存 `org_member.id`。此方法若成立，可一次性关闭 `OA-ENUM-01/02/03`、`OA-STATUS-01/02`、`OA-MEMBER-02`；`OA-FK-01`（`formmain_0171` ↔ `formson_0172` 真实关联列）仍未被该方法覆盖，候选列名 `formmain_id` 明确不得使用。
- **Related constraints**: 加班认定必须同时体现单据授权与实际打卡证据两侧，不以填报值为准（用户 2026-08-08 明确）；未知枚举值 fail-closed，不得降级为默认类别。
- **Current leaning**: 按上述方法建立配置化映射表，取得 distinct 值后一次性签字。
- **Blocked by**: 上条 OA 网络可达性。
- **Resolves when**: `ctp_enum_item.showvalue` 全量 distinct、`col_summary.state` 全量 distinct、`formmain_0171`/`formson_0172` 的 0/1/N 基数证明取得，签字矩阵对应行转 `VERIFIED`。

## OPEN — deployment-safety-decision — dev/test/生产库现状未盘点
- **Date**: 2026-08-08
- **Source**: 用户 2026-08-08 要求核对 dev / test / 生产三库
- **Open item**: 本机 MySQL 8.0.34 在 3306 监听，但当前执行环境无任何可用管理凭据（`root` 无密码认证失败，仓库外未发现凭据文件），与上文 2026-07-25 的 `SHENZHOUHR_MYSQL_ROOT_PASSWORD = missing` 一致。因此 `shenzhou_hr_dev`、`shenzhou_hr_test` 及生产库的存在性、Flyway history 停留版本、期初人员数据是否已导入均未盘点。另需注意本机为 8.0.34，而仓库核心验证基线为 MySQL 8.4 LTS、客户宝塔包为 8.0.45，三者版本均不一致。
- **Related constraints**: 只允许操作 `shenzhou_hr_dev` 与 `shenzhou_hr_test`；不得修改已执行迁移，不得用 Flyway `repair` 掩盖 checksum 差异。
- **Current leaning**: 取得 root 凭据后先执行只读盘点（`flyway_schema_history` 最大版本、`employee`/`organization` 行数），再决定是否需要补导期初数据。
- **Blocked by**: 本机 MySQL 管理凭据在当前执行环境不可用。
- **Resolves when**: 三库的 Flyway 版本与期初人员行数完成只读盘点并记录。

## OPEN — existing-design-boundary — 期初部门人员数据源文件位置
- **Date**: 2026-08-08
- **Source**: 用户 2026-08-08 指明期初数据文件
- **Open item**: 用户指明期初部门与人员数据为本机 `~/Downloads/人员列表_seeyon1.xls`（252,928 B）与 `~/Downloads/departments_seeyon1.xls`（50,688 B），并表示 dev 环境已导入、正式环境同源。两文件均为旧式 `.xls`，而 `docs/decisions/OPEN-DECISIONS.md` 2026-07-24 条目记载 `.xls`/CSV 是否作为首期原生输入尚未裁决，当前 `peopleImport` 首期只支持标准 `.xlsx`。文件位于仓库外，未复制入仓库。
- **Related constraints**: 不得用姓名/部门唯一匹配；不得导入计算结果；真实人员数据不得进入仓库。
- **Current leaning**: 确认 dev 库实际导入行数后，再决定是否需要 `.xls` 原生支持或先行转换为 `.xlsx`。
- **Resolves when**: dev 库人员/组织行数与两份源文件行数核对一致，且 `.xls` 支持范围形成书面决策。

## OPEN — design-decision-to-evaluate — 用户 2026-08-08 口述答复 D/F/G 的题号绑定
- **Date**: 2026-08-08
- **Source**: 用户 2026-08-08 答复「D、方案2」「F、方案3」「G、策略绑定不重要可不做」
- **Open item**: 用户以 `A`–`G` 编号答复，但该编号来自仓库外的 Codex 会话，仓库内 `docs/contracts/2026-08-06-reporting-business-confirmation.md` 使用 `R01`–`R43` 编号，两套编号无法可靠对应。已可确定的是：C 为出勤率（见上文冲突条目）；G 指策略绑定，用户明确其优先级低、不影响整体流程时可不做；E 为 dev/test/生产库盘点（见上文条目）。D 的「方案2」与 F 的「方案3」所对应的具体问题及选项内容在仓库内无对应记录，不做猜测实现。
- **Current leaning**: 报表优先（用户明确「这几个报表一定要优先」），策略绑定按用户意见降级。
- **Blocked by**: 需用户重新给出 D 与 F 的问题原文，或改用 `R01`–`R43` 编号重述。
- **Resolves when**: D/F 的问题与所选方案在仓库内有可复核记录。

## RESOLVED — design-decision-to-evaluate — 打卡配对规则
- **Date**: 2026-08-08
- **Source**: 用户 2026-08-08 确认
- **Resolution**: 按班次时间窗口，同人同日最早一条打卡记录判为上班卡、最晚一条判为下班卡；跨午夜班次按 `startDayOffset` 处理；中间多余打卡记为其他打卡保留原始记录不丢弃。

## RESOLVED — design-decision-to-evaluate — 打卡去重窗口
- **Date**: 2026-08-08
- **Source**: 用户 2026-08-08 确认
- **Resolution**: 同人 + 同设备 + 60 秒内重复打卡合并为一条，保留最早时间戳，其余物理行标记为 DEDUPLICATED 但不删除，以保留原始证据。

## RESOLVED — design-decision-to-evaluate — 非标工号前缀（SZSTSX）人员是否纳入考勤
- **Date**: 2026-08-08
- **Source**: 用户 2026-08-08 确认
- **Resolution**: `SZSTSX` 前缀人员（得力员工表里有、系统 employee 表里无对应记录）**不纳入考勤核算范围**。系统在绑定阶段遇到此类工号 fail-closed，不产生报表行，但保留原始打卡记录以备人工核查。

## RESOLVED — design-decision-to-evaluate — 得力 employee_query 接口作为工号回填来源
- **Date**: 2026-08-08
- **Source**: 实测验证 2026-08-08
- **Resolution**: `POST https://v2-api.delicloud.com/v2.0/employee/query`（`limit`/`offset` 分页，无 Api-Module 头，签名与 CHECKIN 相同）返回得力 `id` ↔ `employee_num` 映射，585 人全覆盖，工号格式与系统一致（SZST/SZJN 等）。打卡记录 `check_data.employee_num` 为空时，用此接口按 `user_id` 回填工号。样本数据在 `tmp/deli-sample/deli-employees.csv`（Git ignore，含真实姓名工号，确认后删除）。
