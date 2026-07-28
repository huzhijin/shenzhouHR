我先只读核对目标测试文件、两个生产组件和现有脚本，然后给出最小文件级计划；这一步不改代码、不扩范围。
当前 UmaDev 状态里残留的是通用 `spec` 模板，明显会扩展成完整执行计划，和本批“只修单测真实性”冲突；按输入约束我不会让它扩大范围。继续只读确认现有测试分组，计划将严格限于单个测试文件。
### 最小计划

- [AttendanceSetupPages.test.tsx](/Users/huzhijin/Downloads/shenzhouHR/frontend/src/features/attendanceSetup/AttendanceSetupPages.test.tsx)
  - 补强 `correctionAsOf` 上下时间界限。
  - 将 latest-request-wins 改为真实 B 先完成、A 后完成/拒绝。
  - 分开验证 kind 切换清除 result、error、loading，并用可见 kind 的旧响应检验 token 失效。
  - 保留并核验 route `versionId` 切换；稳定时增加 unmount 后 settle 回归测试。

- 默认不改 `AttendancePolicyPage.tsx`、`PolicySimulationPanel.tsx`；仅当新测试暴露真实缺陷时做最小生产修复。

- UmaDev 按顺序执行并记录退出码、测试数及真实结果：目标测试 → `npm run lint` → `npm run typecheck` → `npm test` → `npm run build` → `npm run build:demo`。最终附精确 diff 和旧测试假绿原因；不扩展到其他治理项。