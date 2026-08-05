# OpenDesign 统一视觉与交互验收记录

## 验收范围

- 登录：`frontend/src/features/auth/LoginPage.tsx`
- 工作台：`frontend/src/features/wave7/OpenDesignWorkbenchPage.tsx`
- 考勤大屏：`frontend/src/features/wave7/AttendanceBigScreenPage.tsx`
- 独立报表：`frontend/src/features/reports/CustomerReportCenterPage.tsx`
- 统一应用外壳：`frontend/src/shared/components/AppShell.tsx`
- 全局演示主题：`frontend/src/styles/global.css`

## 设计参考

- 登录：`design/open-design/v1.9/v19-od-01-login-shell.html`
- 工作台：`design/open-design/v1.9/v19-od-02-role-workbench.html`
- 报表与异常页：`design/open-design/v1.9/v19-od-12-exceptions-close-reports.html`
- 大屏：`design/open-design/v1.9/v19-od-15-attendance-screen.html`

## 视觉基线

- 视口：1280 × 720，浏览器缩放 100%。
- 主色：深蓝 `#00265f`、功能蓝 `#25449a`。
- 页面背景：白色工作区与浅灰蓝画布。
- 容器：1px 边框、6–8px 圆角、紧凑阴影。
- 标题：24px 无衬线字，避免营销式大标题。
- 表格：紧凑行高、明确表头、状态色只承担业务语义。
- 大屏：固定 1920 × 1080 舞台，按视口等比缩放。

## 设计比对

- 工作台与 `V19-OD-02` 在同一视口逐项比对：顶部栏、侧栏、指标卡、优先处理、数据新鲜度和跨模块待办结构一致。
- 大屏与 `V19-OD-15` 在同一视口逐项比对：顶部范围切换、六项 KPI、趋势、异常结构、部门对比、加班、月结和数据来源结构一致。
- 报表以 `V19-OD-12` 的白底深蓝查询页为视觉基线，保留客户样表的独立表格内容、浅青考勤表头和橙色年假表头。
- 登录以 `V19-OD-01` 为视觉基线，桌面端保留左侧表单与右侧深蓝安全说明区；移动端隐藏说明区。

## 交互验收

- 演示账号登录与退出。
- 工作台总览、部门视角、个人视角切换。
- 工作台进入数据接入、独立报表和考勤大屏。
- 大屏公司与部门范围切换、展示刷新、指标口径弹窗及全屏入口。
- 在线来源与离线电子表格导入链路。
- 六张工作表的真实 Excel 导入模板下载。
- 导入预检、有效行发布及结果反馈。
- 8 张独立考勤报表切换。
- 月份、部门、员工公共筛选。
- 各报表专属筛选、查询、重置和真实 CSV 导出。
- 员工今日状态、个人考勤、假期余额和反馈提交。

## 中文与术语验收

- 客户可见的“法人实体”统一为“公司”。
- 客户可见的来源类型、导入状态、校验状态、班段、方向和时区选项均有中文映射。
- 演示标识、设备、批次和员工标识均使用可理解的中文名称。
- 报表筛选项、表头、单位、状态和反馈文案均为中文。

## 视觉证据

- 工作台参考：`w4-demo-audit/18-od02-reference-same-viewport.png`
- 工作台实现：`w4-demo-audit/19-od02-current-same-viewport.png`
- 大屏参考：`w4-demo-audit/20-od15-reference-same-viewport.png`
- 大屏实现：`w4-demo-audit/21-od15-current-same-viewport.png`
- 报表参考：`w4-demo-audit/22-od12-reference-same-viewport.png`
- 报表实现：`w4-demo-audit/23-report-current-same-viewport.png`
- 合并对照：`w4-demo-audit/24-combined-qa.png`

## 自动化验证

- TypeScript 类型检查。
- ESLint 静态检查。
- 登录、会话、路由授权、工作台、大屏、报表、数据来源、电子表格导入和员工自助定向测试。
- 演示模式与生产模式构建，以及演示数据生产隔离契约。
- 浏览器控制台无应用错误。
- Git diff 空白错误检查。

## 2026-07-30 真实考勤工作台增量验收

### 验收范围

- 管理端真实工作台：`frontend/src/features/dashboard/DashboardPage.tsx`
- 员工本人工作台：`frontend/src/features/self/PersonalAttendanceDashboard.tsx`
- 员工本人路由：`/me/today`
- 员工本人接口：`GET /api/v1/me/attendance-dashboard`

### 同输入视觉比对

- 参考与实现统一使用 1280 × 720 视口。
- 参考图：`/var/folders/tk/5gwscs1s47s6dfbtl0q272cw0000gn/T/shenzhouhr-attendance-big-screen-reference.png`
- 管理端实现图：`/var/folders/tk/5gwscs1s47s6dfbtl0q272cw0000gn/T/shenzhouhr-workbench-prototype-v2.png`
- 员工端实现图：`/var/folders/tk/5gwscs1s47s6dfbtl0q272cw0000gn/T/shenzhouhr-personal-dashboard-v2.png`
- 将参考图与员工端实现图放入同一比较输入检查：深蓝标题区、白色图表面板、紧凑圆角与信息密度一致；员工端按权限边界改为本人 KPI、本人趋势、今日状态和本人异常，不出现组织分布。

### 设计迭代

- 第 1 轮：管理端标题区存在重复元数据且高度偏大；压缩标题区并合并范围、数据日期、版本信息。
- 第 2 轮：员工端部分 KPI 数值换行；收紧字号并保持数值单行展示，复查后无可见截断或重叠。
- 管理端严重程度筛选、异常抽屉和安全报表穿透入口均完成浏览器交互验证。
- 员工端 DOM 与接口响应不包含公司、组织、其他员工身份或异常案件标识；普通员工菜单不包含管理工作台。
- 浏览器控制台应用错误：`[]`。
- 管理端已在 390 × 844 视口完成移动端视觉检查；员工端响应式断点与自动化测试通过，未将本次无效的浏览器视口切换误记为视觉证据。

### 自动化与真实服务验证

- 后端：599 项测试通过，0 失败、0 错误。
- 前端：386 项测试通过，4 项跳过；类型检查、ESLint 与生产构建通过。
- 真实 MySQL、后端与前端均在不执行迁移、不注入演示数据的前提下启动。
- 后端健康检查为 `UP`；真实前端登录页返回 200；通过前端代理访问未登录会话返回预期 401。
- 使用真实技术管理员初始凭据验证登录成功，并进入首次登录强制修改密码流程。

final result: passed

## 2026-08-05 员工组织树与规则编辑增量验收

### 验收范围

- 员工公司/部门树与人员子树筛选：`frontend/src/features/employee/EmployeesPage.tsx`
- 员工子树查询合同：`GET /api/v1/employees?organizationId=...&includeDescendants=true`
- 考勤规则版本编辑引导：`frontend/src/features/attendanceSetup/AttendancePolicyLifecyclePanel.tsx`
- 规则生命周期、绑定与试算区块顺序：`frontend/src/features/attendanceSetup/AttendancePolicyPage.tsx`

### 视觉来源与目标状态

- 来源视觉：`/var/folders/tk/5gwscs1s47s6dfbtl0q272cw0000gn/T/codex-clipboard-N96fVe.png`
- 来源视口：2048 × 1024。
- 目标状态：左侧默认展开全部授权公司/部门，默认选中“全部人员”；右侧显示人员及“所在部门”，点击公司或部门后显示该节点与全部下级组织的人员。
- 实现地址：`http://127.0.0.1:5173/people/employees`。

### 自动化与服务验证

- 员工页组件测试覆盖默认全部人员、完整树展开、节点子树筛选、请求乱序及目录刷新失败状态保持。
- API/后端集成测试覆盖 `includeDescendants=false` 的兼容精确筛选与 `true` 的完整子树筛选。
- 规则页测试覆盖区块顺序、已发布版本不可直接编辑提示、默认版本同步、保存后进入后继草稿、
  路由替换、发布状态绑定门禁及草稿未来生效日校验。
- TypeScript、ESLint、后端构建和服务健康检查通过。

### 同状态视觉比对

- 已打开并检查来源视觉。
- 当前会话的内置浏览器不可用（运行时仅发现外部 Chrome 连接），因此未在未经用户选择的情况下切换浏览器，也未能采集同视口实现截图、合并对照图或浏览器控制台证据。
- 实现截图：未生成（内置浏览器不可用）。
- 交互与控制台：组件/API 自动化通过；浏览器交互与控制台检查未执行。

final result: blocked — 内置浏览器不可用，无法完成同视口截图合并比对；功能与自动化验收不受影响。
