# 神州 HR V1.9｜UmaDev 完整操作手册

## 一、结论

采用“**流程重开，代码不推倒**”：在当前仓库执行 `umadev adopt`，随后新建一个 V1.9 guarded run。不要对旧任务直接执行 `umadev continue`。

原因：当前工程是可复用的阶段性骨架，但业务完成度较低；旧 UmaDev 状态、`docs/docs-confirm` 和 `docs/open-design-prompts.md` 仍以 V1.7 为基线，与本地账号密码、组织只做期初导入、年假累计工龄、配置化规则和薪资前台隐藏等新要求冲突。直接续跑旧状态会把废弃口径带回来；从零重建又会浪费已有技术骨架、权限、审计、设计 Token、测试和迁移。

## 二、运行前保护

### 1. 先做可恢复基线

当前目录不是普通 Git 仓库，`.umadev/checkpoints.git` 不能代替项目级备份。执行下面两种方式之一：

- 将整个 `shenzhouHR` 目录复制为一个只读备份；或
- 初始化 Git，先补全 `.gitignore`，再提交一次“V1.9 改造前基线”。

Git 基线不要包含：

```text
.umadev/
.docx-qa/
backend/target/
frontend/dist/
frontend/node_modules/
node_modules/
.npm-cache/
.env
.env.*
```

不要提交任何密码、令牌、真实员工数据、工资数据或生产配置。若以前在提示词、聊天或文件中粘贴过真实凭据，先轮换凭据。

### 2. 打开人工确认门

当前 `.umadevrc` 中 `auto_approve_gates = true`。运行前将其改为：

```toml
auto_approve_gates = false
```

本项目不适合自动越过文档确认和页面预览确认。

## 三、新建 V1.9 UmaDev 流程

在终端执行：

```bash
cd /Users/huzhijin/Downloads/shenzhouHR
umadev doctor
umadev history
umadev adopt --project-root /Users/huzhijin/Downloads/shenzhouHR
umadev verify --project-root /Users/huzhijin/Downloads/shenzhouHR

PROMPT_TEXT="$(<docs/umadev-v19-master-prompt.md)"
umadev run "$PROMPT_TEXT" \
  --backend codex \
  --slug shenzhou-hr-v19 \
  --mode guarded \
  --project-root /Users/huzhijin/Downloads/shenzhouHR
```

注意：

- `adopt` 只写 UmaDev 的索引、契约和边界资料，不修改业务源码，可重复执行。
- `run` 应创建新的 V1.9 流程并在 `docs_confirm` 停下。
- 不要先执行 `umadev continue`，否则会尝试恢复旧 V1.7 状态。
- 首轮若直接大改源码、迁移或页面，说明没有遵守总提示词，应在当前 gate 使用 `revise` 纠正，不要继续。

## 四、审查 docs_confirm

重点核对下面 13 项：

1. 活动基线明确是 PRD V1.9；
2. 本地账号密码登录，不再写“SSO 待定”；
3. 组织只做期初 Excel 导入，之后本地独立维护；
4. 没有组织持续同步任务，但 OA 考勤业务单据的只读接入仍保留；
5. 夏冬令时由考勤组的班次版本和生效期表达；
6. 晚餐扣除默认覆盖全部考勤组且可配置；
7. 每人每自然月默认 1 次、15 分钟内迟到宽限且可配置；
8. 单边缺卡只影响缺卡工作段是默认值，补正期限和逾期结果可配置；
9. 年假“本公司满一年资格”与“累计工龄档位”已分开建模；
10. 周年发放、下一周年日前失效、二次入职重置均明确；
11. 员工期初工时/调休/年假余额有 Excel 导入和余额流水；
12. 无网络地点和异构考勤机的原始打卡支持 Excel 导入，并有真实模板、字段映射、设备人员匹配、预检、跨来源去重、错误报告、发布、批次追溯和月结保护；
13. 薪资前台完全隐藏，后端为独立默认拒绝权限域。

如果有偏差，使用：

```bash
umadev revise "$(<docs/umadev-v19-docs-revision-prompt.md)" \
  --backend codex \
  --project-root /Users/huzhijin/Downloads/shenzhouHR
```

如不想另建修订文件，也可以直接把下面文本作为 `revise` 内容：

```text
当前 docs_confirm 暂不批准。请保持在当前 gate，只修订文档和规格，不修改业务源码。

请逐条复核 PRD V1.9 与本轮总提示词，输出“问题—原内容—修订内容—受影响验收”的差异表。必须纠正：本地账号密码；组织仅期初 Excel 导入且后续本地维护；OA 考勤业务单据只读接入与组织同步取消的边界；考勤组和季节班次；全组晚餐扣除；每月一次15分钟内迟到宽限；单边缺卡默认仅影响工作段；假别全面配置；年假资格与累计工龄档位分离；周年发放和二次入职；期初时间账户导入；无网络/异构考勤机原始打卡 Excel 导入（真实模板、字段映射、设备人员匹配、预检、API/Excel 跨来源去重、错误报告、发布、批次审计和月结保护）；薪资前台默认不可发现。旧 V1.7 只能标记为历史。修订完成后继续停在 docs_confirm。
```

确认文档正确后再执行：

```bash
umadev continue \
  --backend codex \
  --project-root /Users/huzhijin/Downloads/shenzhouHR
```

## 五、Open Design 的正确接法

完整、可直接粘贴到 Open Design 的长期主 brief 见 `docs/open-design-prompts-v1.9.md`。下面的“纠偏提示词”只适合在已有 V1.7 Open Design 会话中快速切换口径；新建 V1.9 设计项目时优先使用完整主 brief。

若同一项目已经因 DOCX 转换器、macOS 原生解析、`NOT_ACTIVE`、Design System 导入入口或 PRECHECK 卡住，不再重复粘贴旧分步提示词，也不上传 PRD 附件；直接在原会话完整粘贴 `docs/open-design-restart-v1.9.md`，让它以自包含事实包和非阻塞设计规范重新生成唯一的 V1.9 首轮基线。

若 `V19-OD-01` 已完成，希望一次运行连续做到大屏前，不再逐个点击，则使用 `docs/open-design-batch-before-dashboard-v1.9.md`：它会保持各 artifact 独立，批量生成 `V19-OD-02` 至 `V19-OD-14`，打通客户演示所需的跨页面可点击链路，并在 `V19-OD-15` 大屏前停止。

### 1. 不要直接把旧设计拼进代码

旧 `docs/open-design-prompts.md` 属于 V1.7 历史输入。先让 Open Design 审计已有页面，再按 V1.9 修改；不要立即重画全部页面，也不要把生成的静态 HTML 直接覆盖 `frontend/src`。

建议统一保存到：

```text
design/open-design/v1.9/
├── source/
├── desktop/
├── tablet/
├── mobile/
├── states/
├── artifact-index.md
└── design-handoff.md
```

每个页面使用稳定编号，例如 `OD-001-login`、`OD-002-workbench`，并保留源文件、预览图、交互说明、页面状态和断点说明。

### 2. 给 Open Design 的 V1.9 纠偏提示词

```text
继续现有“神州 HR”设计项目，但当前正式基线切换为 PRD V1.9。V1.7 和旧提示词只作历史参考。

本轮先审计现有 artifact，将每个页面标记为 KEEP、MODIFY、RETIRE 或 FUTURE_RESERVED；不要立即重画全部页面。Open Design 只负责视觉、布局和交互，不得更改 PRD 业务规则、数据权限和 API 语义。

必须纠偏：
1. 登录改为本系统独立账号密码登录，不展示 OA 登录或“SSO 待定”。
2. 组织架构改为期初 Excel 导入和本系统独立维护，删除组织持续同步、OA 组织变更同步入口。
3. 特殊城市、特殊人员、夏令时和冬令时统一通过考勤组、班次版本、生效期和适用范围配置。
4. 新增统一规则配置中心：作用范围、版本、生效期、优先级、冲突检测、样例试算、发布、停用、回滚和审计。
5. 新增或完善：组织/员工导入、班组班次、晚餐扣除、迟到宽限、单边缺卡、假别、年假、累计工龄、员工期初时间余额、离线考勤打卡 Excel 导入、导入批次、错误报告、数据质量、异常处理和月结页面。
6. 年假 UI 必须分别表达“最新任职周期本公司满一年资格”和“累计工龄决定档位”，并展示周年发放、下一周年日前失效及二次入职重置。
7. 薪资页面仅保留为 FUTURE_RESERVED 设计资产；当前导航、路由、首页、搜索和普通用户入口均不得出现薪资。
8. 延续现有品牌、设计令牌和企业级高密度后台风格，优先复用统一组件。

每次只完成一个 artifact；每个 artifact 覆盖正常、加载、空数据、失败、无权限、配置冲突、冻结期间和操作成功等适用状态，并给出桌面、平板、手机断点行为。所有产物输出到 design/open-design/v1.9，并维护 artifact-index.md 与 design-handoff.md。
```

### 3. 给 UmaDev 的设计汇总提示词

在 Open Design 产物导入仓库后，把下面内容提交给当前 UmaDev gate；若正在 `docs_confirm`，使用 `umadev revise`；若已进入新的受控实施轮次，则作为该轮唯一授权。

推荐直接执行：

```bash
umadev revise "$(<docs/umadev-v19-open-design-integration-prompt.md)" \
  --backend codex \
  --project-root /Users/huzhijin/Downloads/shenzhouHR
```

```text
本轮只授权汇总 V1.9 Open Design 产物并形成前端实施规格，不授权修改 frontend/src、backend/src 或数据库迁移。

实际读取 PRD V1.9、docs/docs-confirm-v1.9、docs/open-design-prompts-v1.9.md、design/open-design/v1.9 下全部实际产物，以及现有 React 路由、Ant Design 组件、设计 tokens 和 AppShell。

规则：
1. Open Design 仅是视觉和交互参考，不是业务、安全、权限或数据事实来源；发生冲突以 PRD V1.9 和已确认规格为准。
2. 不得把多个独立原型直接拼接，不得把静态 HTML 整体复制进 React。
3. 先统一 tokens、壳层、导航、组件和页面状态，再映射现有 React 架构。
4. 未找到的设计产物必须标为 MISSING，不得凭空宣称完成。
5. 所有薪资设计标为 FUTURE_RESERVED，不进入当前菜单、路由和普通用户能力。

输出到 docs/design-v1.9-integration：artifact 索引、route-to-artifact 矩阵、设计令牌差异、公共组件清单、页面状态矩阵、PRD/设计冲突及裁决、现有页面保留/改造/替换方案、前端实施切片和多断点截图验收清单。离线考勤导入必须单列 route、下载模板、字段/设备人员映射、预检统计、错误报告、跨来源重复冲突、发布确认、批次详情和原始行追溯的状态矩阵。完成后停止，等待人工确认。
```

## 六、在 preview_confirm 怎么验收

UmaDev 完成 spec/frontend 后会停在 `preview_confirm`。此时不要只看首页截图，至少检查：

- 1366×768、1440×900、1920×1080；
- 768～1024 平板；
- 360～430 手机；
- 登录、组织导入、员工维护、规则配置、班组班次、年假和期初余额的主路径；
- 离线考勤导入的模板下载、不同设备文件上传、字段映射、设备人员匹配、预检与错误报告、重复/冲突确认、发布和批次追溯；
- loading、empty、error、403、配置冲突、冻结期间和成功状态；
- 菜单、路由、全局搜索、首页卡片均找不到薪资；
- 改 URL 或 ID 不能看到越权数据，不能只靠前端隐藏。

不符合时使用 `umadev revise "具体问题、路由、期望结果"`，仍停在当前 gate。确认后才执行第二次 `umadev continue` 进入后端、质量和交付阶段。

## 七、建议分波次提示词

即使 UmaDev 可以连续执行，也建议每次只授权一波。每轮都在开头加上这段公共约束：

```text
本轮只授权实施 WAVE-X，不得提前实现后续波次。严格依据 PRD V1.9、已确认的 docs/docs-confirm-v1.9 和 docs/design-v1.9-integration。

在现有代码上增量修改，先检查当前实现和用户改动。每波必须形成可运行的纵向切片：OpenAPI/契约、新增 Flyway 迁移、后端、服务端权限、前端完整状态、测试和说明同步完成。不得修改 V1/V2 迁移；默认值来自版本化配置；不得连接生产系统；不修复无关问题。完成后真实执行适当构建和测试，报告命令、结果、改动文件、未验证项、人工验证入口，然后停止等待确认。
```

### WAVE-1：身份与规则配置底座

```text
实现独立账号密码登录、安全密码哈希、首次改密、失败锁定、密码重置、会话、退出、权限和审计；实现 PolicyTemplate、PolicyVersion、ScopeBinding、草稿校验、发布、生效期、冲突检测、样例试算、停用和回滚；提供登录页与规则配置中心基础 UI。不得实现 OA SSO。
```

### WAVE-2：组织、员工与任职数据

```text
实现致远组织/员工期初 Excel 导入的模板、预检、差异、错误报告、幂等、批次发布和审计；实现本地组织树、岗位、员工、任职周期和 HR 维护的入职前累计工龄；二次入职新建任职周期并保留历史。不得建立组织定时、手动持续或双向同步任务。
```

### WAVE-3：考勤组与规则配置

```text
实现考勤组、有期限的人员分配、工作日历、地点、班次和夏冬令版本；实现默认全组晚餐扣除、每自然月一次15分钟内迟到宽限、单边缺卡补正期限及逾期结果的类型化、版本化配置和预览。覆盖发布冲突、切换生效日与月结保护。
```

### WAVE-4：考勤数据与证据链

```text
实现得力打卡读取的游标、水位、幂等、去重、重试和隔离；实现无网络/异构考勤机原始打卡 Excel 导入，包括版本化模板、厂商字段映射、设备人员绑定、预检、错误报告、文件哈希、批次发布、作废/冲正和审计；实现 OA 请假、加班、外出、出差、补卡等考勤业务单据的只读适配。三类来源进入统一原始层、标准层和证据链，跨 API/Excel 去重但保留全部来源，冻结/月结期间不得静默重算。明确禁止恢复 OA 组织持续同步。没有真实凭据或设备样本时使用显式开发适配器并标记 NOT VERIFIED。
```

### WAVE-5：考勤引擎、异常与月结

```text
实现按计划工作段计算的迟到、早退、缺卡、旷工、请假、外出、出差、加班、餐扣和跨日逻辑；实现每月迟到宽限命中顺序、单边缺卡只影响工作段的默认行为、异常证据、人工调整、重算、月结、反月结、冻结和差异审计。
```

### WAVE-6：假别、周年年假与时间账户

```text
实现可版本化假别策略；实现本公司最新任职周期满一年资格开关、累计工龄档位、周年日发放、下一周年日前失效、二次入职重置、跨档默认下周年生效和闰日规则；实现员工期初工时/调休/年假 Excel 导入、余额流水、有效期、销假和冻结。覆盖所有边界自动化测试。
```

### WAVE-7：员工自助、报表与设计收敛

```text
实现我的考勤、我的假期、反馈进度、部门/公司授权范围报表和响应式页面；按已确认 Open Design 逐路由收敛，覆盖完整状态和多断点。导出必须服务端鉴权、脱敏和审计。不得展示任何薪资入口。
```

### WAVE-8：薪资后端预留

```text
仅实现 PRD V1.9 已确认的薪资服务端领域边界、期间、项目、员工薪资档案、冻结考勤快照映射和核算结果预留；feature flag 默认关闭，PAYROLL 权限域默认拒绝，不注册可见菜单、路由、首页卡片、搜索或普通用户 API，不生成银行付款文件，不得影响考勤验收。
```

## 八、QA 收口提示词

```text
本轮只授权对 V1.9 已实现范围做 QA、缺陷修复和交付收口，不新增业务范围，不部署、不推送、不创建 PR。

必须真实执行并保存证据：
1. 后端 Java 21 环境、./mvnw test、迁移顺序与重复执行、架构依赖和权限负向测试。
2. 前端 npm ci、npm run check、npm run build:demo，以及正式模式与演示模式隔离。
3. OpenAPI 与前后端调用、错误码、分页、长 ID 和权限失败一致性。
4. 年假周年日前一日/周年日/到期日、累计工龄不足10年/正好10年/正好20年、二次入职、闰日、本公司满一年资格开关。
5. 当月第1次与第2次15分钟内迟到、16分钟迟到、跨月和跨考勤组。
6. 单边缺卡补正期内与逾期、单段与多段；全组晚餐扣除在工作日/周末/节假日；夏冬令切换日。
7. 规则版本冲突、回滚、已月结保护；组织/员工与时间账户 Excel 的重复导入、错误行、部分失败、重复发布和幂等。
8. 离线考勤 Excel：同文件重复上传、换文件名重传、API 与 Excel 同一流水、无原始 ID 指纹去重、正常多次打卡不误删、员工/设备未匹配、姓名不一致、不同列名与日期格式、空行、错误行、跨夜班、夏冬令切换、冻结/月结期间上传与发布、部分成功、错误报告、批次作废及原始审计追踪。
9. 修改 URL、employee_id、organization_id 的越权负测；考勤导入的上传、预检、发布、作废、原始文件和错误报告分别鉴权；薪资菜单、路由、搜索和前台入口不可见；服务端不得向无权用户返回薪资。
10. 1366、1440、1920、平板、手机下的布局、表格、弹窗、固定操作区、键盘焦点和无障碍。
11. 扫描会影响当前行为的 V1.7 旧口径；历史资料可以保留，但必须清楚标记已失效。

输出到 docs/qa-v1.9：测试结果、命令和日志摘要、需求—测试追踪、安全复核、UI 截图清单、已知限制、上线阻断项、回滚方案和最终 PASS/BLOCKED 结论。外部接口未真实联调时必须标为 NOT VERIFIED 或 BLOCKED，不能伪造 PASS。完成后停止。
```

最后执行：

```bash
umadev verify --runtime \
  --project-root /Users/huzhijin/Downloads/shenzhouHR

umadev report --review \
  --slug shenzhou-hr-v19 \
  --project-root /Users/huzhijin/Downloads/shenzhouHR
```

## 九、什么时候才考虑从零重建

只有审计证明现有领域模型、权限和工程结构大面积不可复用时才重建。目前现状不满足这一条件。

若未来确实重建，应放到独立兄弟目录，例如 `/Users/huzhijin/Downloads/shenzhouHR-v19-rebuild`，原仓库只读；先输出可复用资产和拒绝复用清单，经人工确认后再搭工程。绝对不要在原目录删除后重建。
