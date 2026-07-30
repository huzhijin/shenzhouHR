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
