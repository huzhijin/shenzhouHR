# WAVE-1 浏览器验收证据

验证日期：2026-07-24

## 当前结论

- demo 模式的生产 React 路由、交互状态、响应式布局和浏览器隔离已经完成验证。
- demo 模式未产生 `/api/v1` 请求，LocalStorage 与 SessionStorage 均为空，浏览器未保存会话令牌或敏感字段。
- 使用清空后的独立浏览器会话遍历关键路由，`agent-browser errors` 为空；控制台仅包含 Vite/React 开发环境信息，没有应用异常或 Ant Design 弃用告警。
- 普通模式已证明会尝试访问真实 `/api/v1/me/capabilities`，但当前 `5173` 进程没有加载代理环境变量且后端未启动，请求返回 404。本项仅证明错误状态可见，不能作为真实联调完成证据。
- 真实 MySQL 登录、首次改密、登录错误、会话过期、401/403 及服务端成功链路仍为 `NOT_VERIFIED`，必须在仓库外数据库凭据可用后重新验证并截图。

## 已验证路由

- `/rules`
- `/rules/templates`
- `/rules/templates/9500000000000000001`
- `/rules/templates/9500000000000000001/versions/9600000000000000001`
- `/rules/templates/9500000000000000001/versions/9600000000000000002`
- `/access/accounts`
- `/access/accounts/9100000000000000001`
- `/access/roles`
- `/access/audit`
- `/access/audit/9400000000000000001`

所有 ID 均为明确标记的合成 demo 数据，不对应真实员工或业务数据。

## 断点与布局证据

| 断点 | 代表截图 | 结果 |
| --- | --- | --- |
| 1366×768 | `demo/rules-1366x768.png` | 通过 |
| 1440×900 | `demo/rules-1440x900.png` | 通过 |
| 1920×1080 | `demo/rules-1920x1080.png` | 通过 |
| 1024×768 | `demo/policy-templates-1024x768.png` | 通过 |
| 768×1024 | `demo/accounts-768x1024.png` | 通过 |
| 390×844 | `demo/rules-390x844.png`、`demo/accounts-390x844.png` | 通过 |

每条被验证路由均满足：

```text
document.documentElement.scrollWidth <= document.documentElement.clientWidth
```

390×844 下，菜单、改密和退出按钮的边界均处于 viewport 内；表格降级为字段卡片，移动抽屉与改密弹窗可完整操作。

手机断点逐路由检查可见按钮、输入、选择器、日期和数值控件；所有可操作按钮均不小于 `44×44px`，弹窗关闭、取消和确认按钮也满足 44px 最小触控尺寸。

键盘验证结果：

```text
首次 Tab => 焦点进入“跳到主要内容”
focus outline => 3px solid
Enter => document.activeElement.id == "main-content"
```

对应截图为 `demo/keyboard-focus-skip-link-1440x900.png`。

## 交互与状态证据

| 状态 | 截图 |
| --- | --- |
| 草稿编辑 | `demo/policy-version-draft-1440x900.png` |
| 发布版本 | `demo/policy-version-published-1440x900.png` |
| 校验成功 | `demo/policy-validation-success-1440x900.png` |
| 作用范围冲突 | `demo/policy-conflict-1440x900.png` |
| 影响预览 | `demo/policy-impact-preview-1440x900.png` |
| 样例试算成功 | `demo/policy-simulation-success-1440x900.png` |
| 发布确认 | `demo/policy-publish-confirmation-1440x900.png` |
| 账号锁定 | `demo/accounts-1440x900.png` |
| 角色权限 | `demo/roles-1440x900.png` |
| 审计列表 | `demo/audit-1440x900.png` |
| 审计详情 | `demo/audit-detail-1440x900.png` |
| 移动抽屉导航 | `demo/mobile-navigation-390x844.png` |
| 移动改密弹窗 | `demo/change-password-dialog-390x844.png` |
| 字段错误语义关联 | `demo/change-password-validation-390x844.png` |
| 普通模式后端不可用 | `ordinary-backend-unavailable-1440x900.png` |

改密空表单提交后，三个输入框均具有 `aria-invalid="true"`，并分别通过 `aria-describedby` 指向对应错误信息。

## demo 隔离证据

独立浏览器会话 `wave1demo-clean` 遍历关键路由后的结果：

```text
network requests --filter /api/v1 => No requests captured
storage local => No storage entries
storage session => No storage entries
errors => empty
```

demo 模式仅加载前端资源，不调用后端，也不写 MySQL。

## Token 对比度

对 `frontend/src/styles/design-tokens.json` 中全部 `surface/on-*`、状态、品牌、焦点和文本配对按 WCAG 相对亮度公式重新计算：

```text
PAIRS=17
FAILURES=0
MINIMUM=4.76:1（text.muted/on-muted）
DANGER=4.80:1（brand.danger/on-danger）
```

17 组实测值均与 Token 中记录值一致，正文前景配对均达到 4.5:1。

## 必须补做

以下项目只有真实 `shenzhou_hr_dev`、应用账号、后端和普通 Vite 代理均启动后才能完成：

- `/login` 桌面和移动截图；
- 错误密码、账号锁定、首次改密、会话过期；
- 真实账号、角色、审计和规则数据的 MySQL 可追溯性；
- 401、403、404、乐观锁冲突和服务端字段错误；
- 登录、改密、退出、会话撤销的 Cookie、CSRF 和审计链路；
- 普通模式浏览器控制台、网络请求和 correlation id 追踪；
- 真实成功状态截图。

这些项目当前必须保持 `NOT_VERIFIED`，不得用 demo 证据替代。
