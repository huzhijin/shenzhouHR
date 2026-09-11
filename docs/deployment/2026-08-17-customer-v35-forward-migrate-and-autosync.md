# 客户库 V35 前向迁移与得力/OA 自动同步

客户 `shenzhou_hr` 在 2026-08-17 仍停在 Flyway **V35**。仓库代码已按 V48 契约开发，并新增 **V49**（请假/销假流水号入库）。**不要把生产写成已经迁到 V48。**

## 1. 先备份再迁

在宝塔/客户机上先做完整逻辑备份，确认可恢复后再跑 Flyway：

```sql
SELECT version, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 15;
```

通过条件：备份完成后，再执行 V36–V49。迁完应看到 `48` 和 `49` 且 `success = 1`。此时才谈补卡表、出勤天、SYSTEM 主体、销假流水号列。

## 2. 部署后打开自动同步（Q15）

Java 属性默认保持关闭：

- `shenzhouhr.deli.auto-sync-enabled` 默认 `false`
- `shenzhouhr.oa.auto-sync-enabled` 默认 `false`

**切到客户环境后**用启动参数打开，不要把 key/secret 写进仓库：

```
--shenzhouhr.deli.auto-sync-enabled=true
--shenzhouhr.oa.auto-sync-enabled=true
--shenzhouhr.integrations.deli-eplus.enabled=true
--shenzhouhr.integrations.oa-mysql.enabled=true
```

App-Key / App-Secret、OA 只读口令只放本机 `0600` 环境文件或宝塔私密配置。

## 3. 得力人员绑定

人员用 `POST /v2.0/employee/query` 的 `employee_num` 绑本地 `employee.employee_number`。部门 `POST /v2.0/department/query` 用于对照组织，不单独写库。打卡 `checkin_query` 从 `next_id=0` 可拉全量；失败页不得推进水位。

## 4. 本轮验收

Q13=A：内网单公司样本人对账即可。连上客户 OA 只读库、重跑得力/OA 同步后再对账，不等于生产已上线。
