## ADDED Requirements

### Requirement: 浏览器矩阵必须覆盖批准平台和视口
验收矩阵 SHALL 覆盖最近两个主版本 Chrome/Edge、iOS Safari、Android Chrome，以及 390×844、768×1024、1024×768、1366×768、1440×900、1920×1080 和 3840×2160 视口。

#### Scenario: 任一必需浏览器或视口未执行
- **WHEN** 结果集中缺少批准平台或视口且没有获批豁免
- **THEN** 浏览器总门 MUST 为 `NOT_VERIFIED`

### Requirement: 浏览器矩阵必须覆盖角色、状态和安全恢复
每个关键 P0-A route SHALL 覆盖允许/拒绝角色、loading、empty、error/retry、403、frozen、键盘焦点、无横向溢出、注销/撤权后缓存恢复和敏感字段检查。

#### Scenario: 注销后浏览器恢复旧敏感页面
- **WHEN** 用户查看敏感内容后注销、撤权、后退或恢复冻结标签页
- **THEN** 页面 MUST NOT 恢复受保护数据，且对应安全用例失败时阻止发布

#### Scenario: PAYROLL 在 P0-A 前台可发现
- **WHEN** 菜单、路由、搜索、通知、预取、缓存、埋点或普通 API 中出现 PAYROLL 明细能力
- **THEN** 浏览器/发现性叶子 MUST 为 `FAIL`

### Requirement: 浏览器证据必须来自真实集成运行
每个结果 SHALL 记录 integrated commit、run ID、浏览器/OS/设备、route、身份类别、数据状态、截图或 trace SHA、console/page error 和网络摘要；demo 或静态原型不得作为最终通过证据。

#### Scenario: 只有 demo 截图
- **WHEN** 视觉和交互检查未连接 W7 FINAL 真实 API 与 MySQL 8.4 数据环境
- **THEN** 对应结果 MUST 为 `NOT_VERIFIED`
