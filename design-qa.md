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

final result: passed
