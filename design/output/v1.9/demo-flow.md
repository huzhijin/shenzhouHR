# 神州 HR V1.9 · Demo Flow

模式：`PROTOTYPE_SIMULATION`  
重置方式：任一 V19-OD-02～V19-OD-14 页面右下角 `PROTOTYPE_ONLY · 演示场景` → `重置演示数据`。  
说明：状态仅保存在浏览器 `localStorage`，不代表真实后端、接口、权限或持久化已完成。

## FLOW-01 · HR 期初建档到周年年假

- 演示角色：HR 管理员（`hr`）
- 起点：`/login` → `V19-OD-01`
- 前置状态：`ORG-INIT-DEMO-001 = ready_to_publish`
- 点击步骤：登录 → HR 工作台 → “期初组织待发布” → 下载模板/上传 → 字段映射 → 预校验 → 差异确认 → 发布 → “查看本地组织” → 制造一部 → `EMP-DEMO-001` → 任职周期 → 入职前累计工龄 → “查看周年年假试算”
- 目标 artifacts：V19-OD-02 → 03 → 04 → 08
- 预期变化：组织批次变为 `published`；本地组织树可用；员工甲显示最新任职周期和累计工龄 120 个月；年假命中 10 天 / 80 小时档位。
- 返回：年假试算返回员工详情；员工详情返回组织树；上下文保留 `role=hr&department=制造一部&employee=EMP-DEMO-001`。
- 未实现：真实 XLSX 解析、服务端导入任务和账号落库为 `ENGINEERING_ASSUMPTION / NOT_VERIFIED`。

## FLOW-02 · 离线打卡导入到证据链

- 演示角色：考勤管理员（`attendance`）
- 起点：`/workbench` → V19-OD-02
- 前置状态：`ATT-XLS-DEMO-001 = ready_to_publish`
- 点击步骤：“待发布离线批次” → 标准模板 → 上传并选择离线厂区 A → 字段映射 → 设备人员匹配 → 预检 → API/Excel 重复对比 → 发布 → 批次详情 → 原始行 → “查看考勤证据链”
- 目标 artifacts：V19-OD-02 → 10A → 11
- 预期变化：批次从草稿/待确认变为 `published`；412 条可发布事实进入原始事实层；跨来源重复保留两方证据但只形成一个规范事件。
- 返回：证据链返回批次原始行；工作台上下文保留 `month=2026-07&batch=ATT-XLS-DEMO-001`。
- 未实现：真实文件下载、病毒扫描、对象存储、设备厂商解析器和服务端幂等为 `ENGINEERING_ASSUMPTION / NOT_VERIFIED`。

## FLOW-03 · 规则组合到发布

- 演示角色：考勤管理员（`attendance`）
- 起点：`/rules` → V19-OD-05
- 前置状态：`ATT-POLICY-DEMO-V3 = draft`
- 点击步骤：统一规则中心 → 考勤组 → 夏/冬令班次版本 → 晚餐扣除 → 迟到宽限 → 单边缺卡 → 员工甲试算 → 影响预览 → 发布确认
- 目标 artifacts：V19-OD-05 → 06 → 07
- 预期变化：规则 V3 从 `draft` 变为 `published`，生效日期 2026-08-01；已冻结的 2026-07 不被改写。
- 返回：策略页返回规则中心；上下文保留 `employee=EMP-DEMO-001&month=2026-07`。
- 未实现：真实规则引擎执行、冲突求解、回滚事务和历史重算为 `ENGINEERING_ASSUMPTION / NOT_VERIFIED`。

## FLOW-04 · 异常处理到月结报表

- 演示角色：考勤管理员（`attendance`）
- 起点：`/attendance/exceptions` → V19-OD-12
- 前置状态：缺卡 3 条；离线批次未发布；月结 `blocked`
- 点击步骤：异常待办 → 员工甲缺卡证据 → 补正 → 人工调整确认 → 重算差异 → 月结检查 → 查看阻断 → 跳转离线批次并发布 → 返回月结 → 完成月结 → 部门报表
- 目标 artifacts：V19-OD-12 → 11 → 10A → 12
- 预期变化：缺卡异常减少；重算生成差异；离线批次发布后阻断数降为 0；2026-07 月结变为 `closed`；部门报表可查看。
- 返回：报表返回月结；跨页保留 `month=2026-07&department=制造一部`。
- 未实现：真实调整审批、重算队列、数据库冻结、反月结事务和导出审计为 `ENGINEERING_ASSUMPTION / NOT_VERIFIED`。

## FLOW-05 · 员工移动端解释与反馈

- 演示角色：普通员工（`employee`）
- 起点：`/me/today` → V19-OD-13
- 前置状态：员工甲本月迟到宽限已使用 1 次；7 月 18 日下班缺卡待补正；年假 80 小时。
- 点击步骤：我的今日 → 月历 → 7 月 18 日记录 → “为什么” → 假期余额 → 周年年假发放/到期解释 → 提交反馈 → 反馈进度
- 目标 artifacts：V19-OD-13 → 11 → 13；周年年假完整解释在员工端对话框内完成，不进入管理配置页。
- 预期变化：解释页显示事实、`ATT-POLICY-DEMO-V3`、计算过程和下一步；反馈 `FEEDBACK-DEMO-001` 进入“待部门确认”。
- 返回：证据/年假解释返回员工端对应标签，保留 `role=employee&employee=EMP-DEMO-001&month=2026-07`。
- 未实现：真实消息推送、附件上传、反馈审批与移动端离线缓存为 `ENGINEERING_ASSUMPTION / NOT_VERIFIED`。

## 主链路断点声明

- 五条主链路的页面与动作入口全部存在；V19-OD-16 终检未发现未说明死链。
- 所有非主链路未实现按钮必须显式提示“原型未接入 / 待工程实现”。
- `V19-OD-15` 已登记为独立 `/display/attendance` 只读 Live artifact，不属于五条产品操作链路，也不进入产品导航。

## V19-OD-16 最终链路核验

检查日期：2026-07-22

| 链路 | 注册 route | 目标文件 | 来源 / 返回 | 共享主键 | 结果 |
|---|---:|---:|---:|---:|---|
| FLOW-01 | 6 / 6 | 5 / 5 | PASS | ORG-INIT-DEMO-001、EMP-DEMO-001 | CLICKABLE / PASS |
| FLOW-02 | 4 / 4 | 3 / 3 | PASS | ATT-XLS-DEMO-001、ATT-EVENT-DEMO-001 | CLICKABLE / PASS |
| FLOW-03 | 3 / 3 | 3 / 3 | PASS | ATT-POLICY-DEMO-V3 | CLICKABLE / PASS |
| FLOW-04 | 5 / 5 | 2 / 2 | PASS | 2026-07、ATT-XLS-DEMO-001 | CLICKABLE / PASS |
| FLOW-05 | 5 / 5 | 2 / 2 | PASS | EMP-DEMO-001、FEEDBACK-DEMO-001 | CLICKABLE / PASS |

结论：五条主链路全部连续可点击；所有状态变化继续标记为 `PROTOTYPE_SIMULATION`。重置后恢复 `demo-data-registry.json` 所登记的统一初始状态。
