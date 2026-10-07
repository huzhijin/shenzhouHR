## Why

客户报表部门列仍按上一版规则丢掉一级部门，年休假还拆成两列，长路径把其它表撑开。导出走另一套投影字段，对不上当前屏幕（尤其考勤明细矩阵的格子色）。同时需要整站白天/黑夜换肤，对外名称改为「神州考勤系统」。

## What Changes

- **部门列**：所有展示报表（含年休假）用同一列「部门」，短名从一级拼到叶子，例如 `服务中心-工程二部-RF-B组`；更深继续 `-` 拼接。跳过公司根。组织树和人员目录仍用短名，不拼路径。
- **换行**：部门过长时在层级之间换行，不切断 `RF-B组` 这类名字里自带的 `-`。表行高跟着长，冻结列和日期格对齐。
- **导出**：导出必须是当前正在看的那张表（列、筛选后的行、部门路径、格子语义色）。**BREAKING**（对当前 LIVE xlsx）：不再导出投影字段平铺表。皮肤无论白天黑夜，文件永远是白天白底。
- **换肤**：整站可切白天/黑夜，含登录、侧栏、报表、考勤大屏。格子语义色（假别/迟到/加班等）不另做夜版。
- **名称**：用户可见产品名改为「神州考勤系统」。公司法定全称、仓库和部署项目名不动。

上一版 `report-department-tree` 里「部门列从二级开始拼、省略一级」作废，改由本 change 的全路径规则替代。筛选树、组织页树形结构不改。

## Capabilities

### New Capabilities

- `report-department-path`: 报表部门列全路径、年休假并列、层级换行
- `report-export-display-parity`: 导出与当前可见表一致，且固定白天白底
- `site-day-night-theme`: 整站（含登录与考勤大屏）白天/黑夜换肤
- `product-brand-name`: 用户可见产品名为「神州考勤系统」

### Modified Capabilities

- 无已发布主规格。在途 `report-department-tree` 的部门拼接句由 `report-department-path` 替代，不另写主库 delta。

## Impact

- 后端 `DepartmentPathNames`、身份闭包写入日事实/月矩阵的 `organizationName`、年休假行映射、`XlsxAttendanceReportExportEncoder`（需按当前可见表画册，考勤明细走月矩阵格子而不是 `ATTENDANCE_DETAIL` 日报字段）
- 前端报表中心各表部门列与年休假表头、`customerReports.css` 换行、导出绑定、整站 token / Ant Design 主题 / 顶栏切换、登录与考勤大屏、`app.name` / `app.shortName` / `index.html` title
- 不改组织树结构、人员目录短名、计算引擎公式、OA 同步、密码与会话
