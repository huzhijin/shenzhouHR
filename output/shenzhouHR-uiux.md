# 神州 HR V1.9 UI/UX 合同

## Visual direction

`product` — 用户进入系统是为了完成高密度、可追溯的人力资源作业，不是为了浏览营销叙事。方向为“精密制造控制台”：克制、紧凑、可审计，以状态、版本、生效期和数据来源为主要视觉语义。

## 设计系统来源

- 设计系统：`design/open-design/v1.9/design-system/DESIGN.md`
- Token 源：`design/open-design/v1.9/design-system/design-tokens.json`
- CSS Token：`design/open-design/v1.9/design-system/tokens.css`
- 工程 Token：`frontend/src/styles/tokens.css`
- 设计交接：`design/open-design/v1.9/design-handoff.md`

Open Design HTML 是交互评审产物，不是生产前端源码；其真实存储位于 `design/output/v1.9/`，原 `design/open-design/v1.9/` 路径保留为兼容链接。

## 工程约束

- 前端使用 Tabler Icons，统一 `stroke=1.75`，不使用 emoji 或手绘功能图标。
- 所有颜色、字体、间距、圆角和动效从语义 Token 读取；表面均有成对前景色。
- 当前 React 路由只交付 `/organization` 与 `/employees`，服务端菜单和能力决定其可发现性。
- 异步页面必须覆盖 loading、empty、error 与 retry；致命错误由 `AppErrorBoundary` 提供恢复。
- 交互必须具备 hover、focus-visible、active、disabled；正文对比度至少 4.5:1，UI/大字至少 3:1。
- 生产模式调用真实 `/api/v1` 接口；demo mode 只能通过显式 Vite mode 启用，不得作为生产数据来源。

## 当前实现锚点

| 能力 | 真实源码 |
|---|---|
| 路由与会话加载 | `frontend/src/app/App.tsx`、`features/session/useSession.ts` |
| 组织树 | `features/organization/OrganizationPage.tsx`、`organizationApi.ts` |
| 人员分页 | `features/employee/EmployeesPage.tsx`、`employeeApi.ts` |
| 统一异步状态 | `shared/components/StatePanel.tsx` |
| 致命错误恢复 | `shared/components/AppErrorBoundary.tsx` |
| 壳层与图标 | `shared/components/AppShell.tsx` |
| 语义 Token | `styles/tokens.css`、`styles/global.css` |

Open Design V19-OD-00～16 所描述的考勤、规则、导入、移动端和大屏仍是设计交接，不等同于当前 React/API 已实现功能。
