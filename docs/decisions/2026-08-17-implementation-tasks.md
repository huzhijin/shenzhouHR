# 2026-08-17 确认范围 → 代码修改任务

对照：已确认决策 vs 当前代码 / 客户环境 `58.220.159.170:23273`。  
验收标准仍是 **Q13=A**：内网单公司样本人对账，不等于生产上线。

## 客户环境已核实（2026-08-17）

| 项 | 事实 |
|---|---|
| HR 登录 | `szsc_admin_*` 可登录，82 项能力 |
| 得力来源 | `DELI_EPLUS_SZSC` **ACTIVE**，`lastSuccessfulSyncAt=2026-08-10T02:22:42Z` |
| 得力任务（9 条） | 成功 1、部分隔离 1、失败 7（`DELI_SYNC_FAILED` 4 / `DELI_INVALID_RESPONSE` 2 / `DELI_INTEGRATION_DISABLED` 1） |
| 最近一次同步 | **2026-08-10 失败**，0 页 0 条 |
| OA 单据列表 | 该来源 `documents` **0 条** |
| 报表公司 | 江苏神州半导体可查询 2026-08 |
| 库版本 | phpMyAdmin 仍为 **Flyway V35**，无 V47/V48 |

结论：得力接口侧你认为已能拉全量，但**客户机上的同步任务没有拉全、且 8/10 后失败**。要出可信报表必须先在服务器重跑同步（并修失败原因），不能只改计算器。

---

## 已对齐、本轮不必改口径的

| 规则 | 代码 |
|---|---|
| 出勤率按天 | `ATTENDANCE_RATE_ACTUAL_DAYS_OVER_SCHEDULED_DAYS_V2` |
| 病假算出勤 | `LeaveType.countsAsAttendance()`，病假为 true |
| 迟到≥30 转旷班 | `DeterministicAttendanceCalculator.addLateOrConvertToAbsence` |
| 缺卡全天 2 次 | projector 按 ENTRY/EXIT 两侧计 |
| 不接出差 TRIP | OA SQL `document_type <> 'TRIP'` |
| 外出须单+卡 | `OUTING_APPROVED_WITH_PUNCH` |
| 加班三分类 | 报表列 PAID/COMPENSATORY/VOLUNTARY |
| 每月 1 次补卡 | `PunchCorrectionQuotaService`（依赖 V48 表） |
| 实时读报表不需发布 | `RealtimeAttendanceReportSnapshotService` |
| 正式 UI | `/attendance/reports` → CustomerReportCenter |

---

## 必须改的任务（按推荐顺序）

### T1. 销假按「实际请假时段」替换并相加
**状态：本会话开始改**

- [x] T1.1 去掉 `OaDocumentConverter`「看见销假就整月失败关闭」
- [x] T1.2 无销假：请假单开始/结束
- [x] T1.3 有销假：用销假 `field0086/87` 实际开始/结束替换原请假
- [x] T1.4 多张有效销假：各实际时段相加，重叠做并集
- [ ] T1.5 入库时带上请假流水号 / 原请假流水号（现 SQL 已读、构造时丢掉）
- [ ] T1.6 流水号缺失时按同一员工回退，不得整月失败
- [x] T1.7 单测覆盖：无销假 / 一张 / 两张重叠 / 两张不相交（`OaDocumentConverterOvertimeTest` 已绿）

### T2. 班次显示真实名称
- [ ] T2.1 `ShiftSegmentRow` 带上班次名（`shift_template.template_code` 或展示名）
- [ ] T2.2 `FullCalculationEngineOrchestrator` 不再写死「计算班次」
- [ ] T2.3 月矩阵/明细断言真实班次名

### T3. 报表中心补深链 + LIVE 导出（Q6=A，Q4=B）
- [ ] T3.1 `CustomerReportCenterPage` 读取 `reportType/period/companyId/expectedProjectionVersion`
- [ ] T3.2 `AttendanceReportExportService` 绑 LIVE snapshot token，不再读已发布投影
- [ ] T3.3 真实模式显示导出走正式 API（禁止再走旧投影零值）
- [ ] T3.4 看板下钻落到同一 LIVE token

### T4. Dashboard 切同一实时快照（Q3=C）
- [ ] T4.1 `AttendanceDashboardService` 走 realtime snapshot，不再 `PROJECTION_NOT_READY`
- [ ] T4.2 与报表同一公司月、同一 token
- [ ] T4.3 无数据/同步失败用明确错误，不用空白当「未发布」

### T5. 来源失败可见 + 可手动重试（Q14）
- [ ] T5.1 报表/来源页展示 `safeErrorSummary` 与最近失败时间（客户现有 7 条失败几乎看不见原因细节）
- [ ] T5.2 一键重跑得力/OA（已有 `POST /api/v1/attendance-source-jobs`，前端要接上）
- [ ] T5.3 部署默认打开自动同步（Q15=A；代码默认仍是关）

### T6. 得力「能拉全量」落到这套库
- [ ] T6.1 查清客户 `DELI_SYNC_FAILED` / `DELI_INVALID_RESPONSE` 根因（分页字段、签名、开关）
- [ ] T6.2 用游标 `next_id` 拉完全量，失败不推进水位
- [ ] T6.3 工号匹配率统计后再绑定（历史 H* vs SZ/C/D/N）
- [ ] T6.4 客户环境重跑同步，核对条数与得力后台

### T7. 员工自助 /me/*（Q5=C）
- [ ] T7.1 去掉 `wave7Gateway` 固定 503
- [ ] T7.2 今日/记录走同一实时日事实
- [ ] T7.3 假期走年假账户 API（已有管理端）
- [ ] T7.4 反馈：有则接，无则菜单暂隐并写明（勿假页面）

### T8. 环境前置（不写业务代码）
- [ ] T8.1 客户库 **备份后** 前向迁 V36–V48（现停 V35）
- [ ] T8.2 迁完再验补卡表、出勤天、SYSTEM 主体
- [ ] T8.3 Q16=B：正式预发/生产窗口仍等内网对账后

### T9. 未提交前端一并收（Q19）
- [ ] T9.1 账号/员工/组织树改动与报表主线同一批验证，不拆丢

---

## 建议本周切片

1. **T1 + T2**（算对、班次名对）  
2. **T5 + T6**（先有打卡再谈报表数字）  
3. **T3 + T4**（看板/导出与报表同一 LIVE）  
4. **T7** 自助  
5. **T8** 由你在宝塔终端执行迁移  

发布接口（Q2=C）本周只做到「UI/读路径不依赖」；不删投影表。
