## Context

报表部门路径今天由 `DepartmentPathNames` 从闭包拼出，但 `fromRootToLeaf` 在超过一级时 `subList(1, size)`，所以 `服务中心 / 工程二部 / RF-B组` 显示成 `工程二部-RF-B组`。身份闭包改写后写入日事实，月矩阵和各投影的 `organization` 都吃这个字符串。年休假仍是「一级部门 / 二级部门」两列。

页面上月度考勤明细走 `month-matrix`（一人一行、上下段色）；LIVE 导出走 `ATTENDANCE_DETAIL` 投影 + `XlsxAttendanceReportExportEncoder` 字段平铺。Demo 导出是无色 CSV。矩阵部门列已允许换行且 `overflow-wrap: anywhere`，其它表 `white-space: nowrap`。

整站没有用户可切的皮肤：token 只在 `:root` 浅色；正式侧栏菜单是 Ant Design `dark`，演示是 `light`。产品文案是 `神州 HR 管理系统` / `神州HR`。

## Goals / Non-Goals

**Goals:**

- 一条路径函数，所有客户报表（含年休假、导出）共用全路径。
- 部门只在层级边界换行；`RF-B组` 内的 `-` 不当分隔符。
- 导出 = 当前可见表的白天白底版本，考勤明细带格子色。
- `html[data-theme]` 白天/黑夜覆盖登录、壳、报表、考勤大屏；语义色不换盘。
- 用户可见产品名为「神州考勤系统」。

**Non-Goals:**

- 不改组织树/人员目录为拼路径。
- 不改计算引擎、OA 同步、密码、会话。
- 不做账号级主题同步（只浏览器持久化）。
- 不改仓库名、包名、宝塔项目名、公司法定全称。
- 不把 Wave7 旧 `ReportsPage` 改成客户样表。
- 不把年休假筛选改造成另一套组织树（可继续用现有一级/二级筛选）。

## Decisions

### 1. 路径从一级拼到叶子，分段保存

`DepartmentPathNames.fromRootToLeaf` 改为 `String.join("-", names)`（`names` 已去掉 COMPANY）。`Levels.reportDepartment()` 不再从二级截。同时保留 `levelOne…group` 供内部，但报表展示与导出只用全路径。

分段列表（`List<String> segments`）与拼接字符串一起提供。前端/Excel 换行插入在段与段之间（`<wbr>` / `\n`），永不在段内按 `-` 切。

备选：继续拼字符串、导出时按 `-` split。否决，因为 `RF-B组` 会切开。

### 2. 年休假表丢掉拆列，筛选可留

`annualLeaveRows` 增加与其它表相同的 `department` 全路径；去掉表头「一级部门」「二级部门」。顶部「一级部门 / 二级部门」筛选仍可过滤，不把拆列加回格子。

### 3. 导出按「可见表编码器」而不是投影字段清单

保留现有导出权限、投影版本、query fingerprint、审计元数据。替换的是 **xlsx 画册**：

- `WORK_HOURS` / `LEAVE` / `OVERTIME` / `EXCEPTIONS` / …：按报表中心表头和行映射写，而不是 `ReportField.label()` 平铺。
- `attendance-detail`：**不**用 `ATTENDANCE_DETAIL` 日报字段；绑定当前月矩阵快照，一人一行、日期列、上下段文案和 tone 填色。
- 调色板固定白天客户样表色（与 `customerReports.css` 图例一致），忽略 `data-theme`。
- 部门列 `wrapText` + 段间换行 + 行高随内容。
- Demo 与 LIVE 都出这份 xlsx，不再用无色 CSV 充当客户文件。

备选：前端用当前 DOM/行数据生成 xlsx。否决为主路径，因为权限、分页和审计仍在后端；前端只负责发起与下载。矩阵导出所需 slot 字段已在 month-matrix API。

### 4. 主题用 `data-theme` + CSS 变量，Ant Design 跟着切

`document.documentElement.dataset.theme = 'day' | 'night'`。浅色 token 留在 `:root`，黑夜写在 `[data-theme='night']`。顶栏开关；`localStorage` 键持久化，缺省 `day`。`ConfigProvider` 用 `theme.algorithm` 映射。考勤大屏读同一属性，只换底和默认字，状态色走现有语义 token。

备选：只切 Ant Design algorithm。否决，因为报表和大屏大量自定义 CSS。

备选：系统 `prefers-color-scheme` 自动。本次不做自动跟随，避免导出「白底」和屏幕突然变夜不一致；开关可后续再加「跟随系统」。

### 5. 产品名只改用户可见文案

`app.name` / `app.shortName`、`index.html` `<title>`、登录品牌、移动抽屉、大屏 `aria-label` 改为「神州考勤系统」。`app.companyName` 仍为公司全称。Logo 图文件不强制重绘。

## Risks / Trade-offs

- [矩阵导出体积/样式] SXSSF 流式写对「一格两段填色」不友好 → 矩阵用 XSSF 或有限行缓冲；人数按现有报表分页上限，超限仍走异步任务合同。
- [路径变更后旧测试全红] `DepartmentPathNamesTest` 和 mapper 夹具锁的是「从二级开始」→ 一次性改断言为全路径。
- [黑夜对比度] 自定义页未吃 token 会发灰字 → 任务含登录、壳、报表、大屏四条目视验收，漏网用页面背景/字色变量收。
- [导出与屏幕仍可能漂移] 两套画册 → 矩阵 tone→颜色映射抽成前后端共用常量表（前端 CSS 变量名对 hex，后端 IndexedColors/XSSFColor）。
- [年休假筛选仍叫一级/二级] 与格子「一列」并存可能让人以为列没改 → 筛选标签可保留，表头必须已是「部门」。

## Migration Plan

1. 先改 `DepartmentPathNames` 与测试，再改年休假列与 CSS 换行（屏幕先对）。
2. 再换导出编码器（矩阵单独路径），保留旧审计字段页「口径说明」。
3. 主题与产品名可并行，不依赖导出。
4. 回滚：路径函数与编码器按版本开关或整包回退；主题只是前端，清 `localStorage` 即回白天。

## Open Questions

无阻塞项。未做的显式选择：主题不跟随操作系统；年休假筛选文案暂不改。
