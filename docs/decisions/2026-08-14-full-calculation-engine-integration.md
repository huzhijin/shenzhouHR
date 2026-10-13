# Full Calculation Engine Integration

## 概述

本次重构将 `DeterministicAttendanceCalculator` 完整集成到考勤报表生成流程中，替代了原有的简化 `PUNCH_SPAN_V1` 算法。

## 变更内容

### 1. 数据层扩展

**新增 Row 类型** (`AttendanceReportCalculationRows.java`)：
- `OaDocumentRow` - OA 考勤单据（请假、加班、出差等）
- `ShiftSegmentRow` - 排班工作段
- `AttendancePolicyRow` - 考勤策略配置

**新增 Mapper 方法** (`AttendanceReportCalculationMapper.java` 和对应的 XML）：
- `findEffectiveOaDocuments()` - 查询有效的 OA 考勤单据
- `findScheduledWorkSegments()` - 查询计划工作段
- `findAttendancePolicy()` - 查询考勤策略

### 2. 证据转换

**新建 `OaDocumentConverter`**：
- 将 OA 单据行转换为 `IntervalEvidence`
- 支持映射各种单据类型（LEAVE、OVERTIME、TRIP 等）

### 3. 完整计算编排器

**新建 `FullCalculationEngineOrchestrator`** (标记为 `@Primary`)：
- 组装完整的 `CalculationInputSnapshot`，包含：
  - 排班工作段 (`ScheduledWorkSegment`)
  - 打卡事件 (`PunchEvent`)
  - OA 证据 (`IntervalEvidence`)
  - 考勤策略 (`CalculationPolicy`)
  - 迟到宽限消耗快照 (`GraceConsumptionSnapshot`)
- 调用 `DeterministicAttendanceCalculator.calculate()` 进行日级计算
- 将 `DailyAttendanceResult` 转换为 `VerifiedCalculatedFacts`
- 处理：
  - 首末打卡时间提取
  - 缺卡次数统计
  - 迟到/早退时长（待完善）
  - 异常指纹

### 4. 计算版本

- **Formula Catalog**: `FULL_CALCULATION_V1`
- **Calculation Version**: `ATTENDANCE.FULL_CALC:V1`
- **源版本追踪**：
  - `PEOPLE.EMPLOYEE_VERSION:V1`
  - `PEOPLE.EMPLOYMENT_ASSIGNMENT:V1`
  - `ORGANIZATION.ORGANIZATION_VERSION:V1`
  - `ATTENDANCE.EFFECTIVE_EVENT:V1`
  - `ATTENDANCE.WORK_CALENDAR_DAY:V1`
  - `ATTENDANCE.SHIFT_SEGMENT:V1`
  - `ATTENDANCE.OA_DOCUMENT:V1`

## 待完善项

1. **迟到/早退时长提取**：目前设置为 0，需要从 `DailyAttendanceResult.ruleHits` 或 `items` 中提取实际时长
2. **宽限次数追踪**：`GraceConsumptionSnapshot` 当前使用占位符，需要实现跨天累计逻辑
3. **异常事实生成**：`ProjectionFacts.exceptionFacts` 当前为空列表，需要从 `exceptionFingerprints` 转换
4. **班次标签**：当前使用固定字符串"计算班次"，应从实际排班数据中提取
5. **日类型判定**：当前简化为 WEEKDAY，应使用日历数据的真实日类型

## 测试

- 新增 `FullCalculationEngineOrchestratorTest` 单元测试
- 测试覆盖：
  - 空员工场景
  - 单员工单日完整计算场景（含打卡、排班）

## SQL 查询注意事项

### `findScheduledWorkSegments`
当前实现是简化版本，生产环境需要处理：
- 轮班制度（多班次循环）
- 特殊排班（临时调班）
- 考勤组成员有效期

### `findAttendancePolicy`
当前返回硬编码的默认策略，需要：
- 从 `attendance_policy` 表读取公司配置
- 支持策略版本演进

### `findEffectiveOaDocuments`
通过 `employee_number` 关联，需要确保：
- 员工号在有效期内唯一
- 处理员工号变更场景

## 兼容性

- 原有 `PUNCH_SPAN_V1` 编排器保留但不再使用（`@Primary` 已移至新编排器）
- 新旧算法并存，可通过移除 `@Primary` 回退到旧版本
- 数据库迁移无需变更，新 Mapper 方法使用现有表结构

## 部署

编译通过，无需额外迁移脚本。部署后自动使用新的完整计算引擎。

---

**作者**: Claude Code  
**日期**: 2026-08-14  
**关联**: Wave 9 考勤计算能力增强
