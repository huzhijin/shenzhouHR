# 补卡申请流程

## 适用范围

本文说明员工提交补卡、系统校验月度配额、HR 批准和考勤计算应用的完整流程。业务规则以 [punch-supplement spec](../../openspec/changes/business-rules-alignment-2026-08/specs/attendance-evidence/punch-supplement/spec.md) 为准；接口字段以 [OpenAPI](../../api/openapi.yaml) 为准。

正式运行时主资源为 `/api/v1/attendance/punch-corrections`，业务术语兼容资源为 `/api/v1/attendance/punch-supplement`；未版本化的 `/api/attendance/...` 仅保留给现有客户端。根路径和 `/apply` 提交路径表示同一申请入口，不应重复提交。

## 参与者和权限

| 操作 | capability | 典型角色 |
|---|---|---|
| 查询配额 | `ATTENDANCE_PUNCH_CORRECTION:READ` | 员工本人、部门负责人、HR、系统管理员 |
| 提交申请 | `ATTENDANCE_PUNCH_CORRECTION:CREATE` | 员工本人、部门负责人、HR、系统管理员 |
| 批准申请 | `ATTENDANCE_PUNCH_CORRECTION:APPROVE` | HR、系统管理员 |

服务端在 capability 门禁之外，已经通过 `PeopleRepository.canAccessEmployee` 对目标员工应用 `COMPANY`、`ORGANIZATION`（含受控下级组织）或 `SELF` 范围。提交按补卡业务日、配额查询按目标月月末、审批按申请业务日判断任职范围；越权返回 `403 ACCESS_DENIED`，且提交不会写入申请。客户端隐藏按钮不能替代这些服务端校验。

## 流程总览

```text
员工发现缺卡
  → 查询补卡业务月配额
  → 提交业务日、补卡方向和原因
  → 系统校验日期、工作日、岗位和配额
  → 创建 PENDING 申请（立即占用当月配额）
  → HR 核对排班、原始证据和原因
  → 批准为 APPROVED
  → 计算引擎在数据截止时间内读取已批准申请
  → 生成对应方向的合成打卡证据并重算
  → 缺卡数和出勤结果按新证据更新
```

待审批申请只占配额，不得提前减少缺卡或改变出勤结果。

## 第一步：查询配额

自然月格式固定为 `yyyy-MM`。可使用查询参数或路径参数，两种方式返回相同数据：

```http
GET /api/v1/attendance/punch-corrections/quota?employeeId={employeeId}&month=2026-08
GET /api/v1/attendance/punch-corrections/quota/{employeeId}/2026-08
```

```json
{
  "remainingQuota": 1,
  "usedQuota": 0,
  "totalQuota": 1
}
```

`PENDING` 和 `APPROVED` 各占用配额；`REJECTED` 和 `CANCELLED` 不占用。配额按补卡业务日所属自然月计算，不是滚动 30 天，也不能通过重复调用兼容别名获得第二份配额。

## 第二步：提交申请

```http
POST /api/v1/attendance/punch-corrections
Content-Type: application/json
X-CSRF-TOKEN: {token}
```

```json
{
  "employeeId": "b0000000-0000-0000-0000-000000000001",
  "businessDate": "2026-08-15",
  "punchSide": "BOTH",
  "reason": "上下班打卡均未上传"
}
```

`punchSide` 取值：

- `ENTRY`：补上班侧；
- `EXIT`：补下班侧；
- `BOTH`：补上下班两侧。

系统在接受申请前必须同时通过以下检查：

1. 员工、业务日、方向和原因完整，原因不超过 500 字符。
2. 当前主体具有 `CREATE` capability，且目标员工在业务日属于其 COMPANY、ORGANIZATION 或 SELF 范围。
3. 业务日不是未来日期，且属于当前月或上一个自然月。
4. 业务日是该员工的有效工作日。
5. 员工在该日不是免打卡岗位。
6. 员工在该业务月没有 `PENDING` 或 `APPROVED` 的补卡申请。

成功返回 `201` 和 `PENDING` 申请视图。并发提交由员工级锁和配额重检保护，不能依赖前端先查配额来保证唯一性。

## 第三步：HR 审核与批准

HR 至少核对：员工任职和公司范围、当日排班、得力原始记录、缺失方向、申请原因，以及是否存在设备或批量同步故障。

```http
PUT /api/v1/attendance/punch-corrections/{requestId}/approve
Content-Type: application/json
X-CSRF-TOKEN: {token}
```

```json
{
  "notes": "已核对排班和设备离线记录"
}
```

成功返回 `200` 和 `APPROVED` 申请视图，同时记录审批人、审批时间和备注。服务端先按申请业务日校验审批人对目标员工的 `APPROVE` capability 及员工数据范围；只有 `PENDING` 可以批准，重复批准或并发审批返回冲突，不得覆盖先到的审批结果。

当前公开 REST 合同只包含“批准”，没有公开的拒绝或取消操作。虽然数据库状态模型保留 `REJECTED`、`CANCELLED`，在正式拒绝/取消接口交付前不得用 SQL 手工改状态或删除申请来释放配额。例外处理要求见[补卡配额与 HR 人工例外处理](../operations/punch-correction-manual-override.md)。

## 第四步：进入考勤计算

- 计算只读取状态为 `APPROVED` 且审批时间不晚于本次 `dataAsOf` 的申请。
- `ENTRY` 只补入口侧，`EXIT` 只补出口侧，`BOTH` 补两侧。
- 合成证据必须标识为补卡来源并能追溯申请 ID、审批人和审批时间，不能伪装成得力设备原始记录。
- 批准后需要走支持的重算/投影发布流程；批准接口响应本身不等于历史报表已被改写。
- 标准单工作段中，批准 `BOTH` 后可消除原本两次缺卡；单侧补卡只消除对应一侧。最终结果仍受其他证据和考勤规则共同影响。

## 状态与配额

| 状态 | 含义 | 占用配额 | 影响计算 |
|---|---|---:|---:|
| `PENDING` | 已提交，待 HR 审核 | 是 | 否 |
| `APPROVED` | 已批准 | 是 | 是，进入后续重算 |
| `REJECTED` | 已拒绝 | 否 | 否 |
| `CANCELLED` | 已取消 | 否 | 否 |

## 常见错误处理

| 场景 | 错误码或结果 | 处理 |
|---|---|---|
| 月份不是 `yyyy-MM` | `VALIDATION_ERROR` | 修正格式后重试 |
| 未来日期 | `PUNCH_CORRECTION_FUTURE_DATE` | 不得提交 |
| 超过上月 | `PUNCH_CORRECTION_DATE_TOO_OLD` | 走有审计的人工考勤调整，不得伪造补卡 |
| 非工作日 | `PUNCH_CORRECTION_NOT_WORKDAY` | 核对排班；排班错误先走配置纠正 |
| 免打卡岗位 | `PUNCH_NOT_REQUIRED` | 无需补卡，核对岗位生效区间 |
| 配额已用完 | `PUNCH_CORRECTION_QUOTA_EXHAUSTED` | 查看当月申请；不得删记录绕过 |
| 申请不存在 | `PUNCH_CORRECTION_NOT_FOUND` | 核对 ID 和数据范围 |
| 员工不在主体授权范围 | `ACCESS_DENIED` | 核对 COMPANY、ORGANIZATION/下级组织或 SELF 授权；不得改传其他 employeeId 绕过 |
| 非待审批或并发审批 | `PUNCH_CORRECTION_NOT_PENDING` / `PUNCH_CORRECTION_CONCURRENT_REVIEW` | 刷新申请状态，不重复审批 |

## 月结前复核

- [ ] 当月批准申请数与进入计算的补卡证据数一致。
- [ ] 所有合成证据均可追溯申请和审批信息。
- [ ] 待审批申请没有提前改变缺卡数。
- [ ] 重算和投影发布保留规则版本、数据截止时间和审计原因。
- [ ] 批量设备故障均有独立工单，没有通过删除申请或直接改库绕过配额。
