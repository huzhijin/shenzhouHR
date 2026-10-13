# W9 npm audit applicability

## Result

- Audit date: 2026-07-28
- `brace-expansion`: upgraded from `5.0.7` to `5.0.8`
- `react-router-dom` / `react-router`: upgraded from `7.11.0` to `7.18.1`
- Remaining npm audit report: 2 High findings for `GHSA-qwww-vcr4-c8h2`
- Applicability: `NOT_AFFECTED_BY_CURRENT_USAGE`
- Overall W9 dynamic security gate: `NOT_VERIFIED`

## Evidence and boundary

GitHub's reviewed advisory states that `GHSA-qwww-vcr4-c8h2` affects React Router `>=7.12.0,<8.3.0`, but only applications using unstable RSC APIs: <https://github.com/advisories/GHSA-qwww-vcr4-c8h2>.

神州 HR 前端是 Vite SPA/library mode：

- `frontend/src/main.tsx` 使用 `BrowserRouter`；
- `frontend/src/app/App.tsx` 使用组件式 `Routes` / `Route`；
- `frontend/src` 与 `frontend/vite.config.ts` 不导入 React Router RSC/server framework API、`@react-router/dev` 或 `react-server-dom-*`。

`scripts/release/verify_threats.py` 将上述无 RSC 使用边界纳入静态检查。该裁决只关闭本 advisory 对当前源码的适用性，不把 `npm audit` 的原始结果改写为零，也不替代 W7 FINAL integrated commit 上的 SCA、授权动态威胁测试或发布安全评审。

如未来引入 React Router framework/RSC API，必须删除本裁决、升级到官方 patched version（advisory 当前标为 `8.3.0`）并重新执行全部前端与安全门。

## Regression A/B record

为避免把既有测试问题误归因于安全升级，使用同一 Node `v24.12.0` 和同一命令
`npm test -- --run src/app/App.test.tsx --maxWorkers=1` 做了版本 A/B：

- 基线 `react-router-dom@7.11.0`：19/24 通过，5 个异步路由用例失败；
- 目标 `react-router-dom@7.18.1`：18/24 通过，6 个异步路由用例失败；
- 共同症状为根路由未完成跳转或 lazy 页面停留在 loading，故不能证明失败由 7.18.1 唯一引入。

最终保留 7.18.1，因为它关闭了 7.11.0 上已发布、且不限于 RSC 的多项 High/Moderate advisory；同时把 App 回归真实记录为失败，不能据此宣称前端或发布总门通过。7.18.1 下 lint、typecheck、production/demo build 和 Nginx 合同 3/3 均通过。
