# 张海兰：先拉得力，15:00 OA 整点后再重算

花名册工号 `SZST0303`。得力工号**已经改成 0303**，新卡按工号就能认人。  
8 月如果以前按旧号 `SZST0302` 入库，只点「重新计算」搬不过来。

**现在先修数据、先不要重算。** 用 `scripts/fix-zhanghailan-august-punches.py`（说明见 `docs/deployment/2026-09-01-zhanghailan-fix-punches.md`）。第三节重算留到 OA 15:00 成功之后。

江苏神州公司 ID：`41000000-0000-0000-0000-000000000003`  
姜长波仍是 `SZST0302`，不要把他的卡并到张海兰。

不要做：

- 不要跑全员 `run-deli-identity.sh`（会临时开回放、重算、再重启 jar）
- 不要 `checkin_query_init`
- 不要覆盖 `/opt/shenzhouhr/start-prod.sh`
- 不要在 15:00 OA 任务还在跑或 FAILED 时重算

---

## 一、现在：看她现在挂在谁名下

宝塔终端：

```bash
export MYSQL_PWD='e0e17f3df673a9f8'
MYSQL=/www/server/mysql/bin/mysql
test -x "$MYSQL" || MYSQL=mysql

"$MYSQL" -h127.0.0.1 -P3306 -uroot --default-character-set=utf8mb4 --table shenzhou_hr -e "
SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       employee.employee_id
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.employee_number IN ('SZST0303','SZST0302')
   OR version.display_name IN ('张海兰','姜长波');
"
```

8 月有效打卡分别挂在谁名下：

```bash
"$MYSQL" -h127.0.0.1 -P3306 -uroot --default-character-set=utf8mb4 --table shenzhou_hr -e "
SELECT version.employee_number AS 工号,
       version.display_name AS 姓名,
       COUNT(*) AS 八月有效卡数,
       MIN(event.point_instant) AS 最早,
       MAX(event.point_instant) AS 最晚
FROM effective_attendance_event event
JOIN employee_version version
  ON version.employee_id = event.employee_id
 AND version.effective_to IS NULL
WHERE event.event_kind = 'PUNCH_POINT'
  AND event.point_instant >= '2026-07-31 16:00:00'
  AND event.point_instant <  '2026-08-31 16:00:00'
  AND version.employee_number IN ('SZST0303','SZST0302')
  AND (
        SELECT lifecycle.lifecycle_type
        FROM effective_event_lifecycle_fact lifecycle
        WHERE lifecycle.effective_attendance_event_id =
              event.effective_attendance_event_id
        ORDER BY lifecycle.knowledge_at DESC,
                 lifecycle.effective_event_lifecycle_fact_id DESC
        LIMIT 1
      ) = 'ACTIVATED'
GROUP BY version.employee_number, version.display_name;
"
```

怎么读：

| 结果 | 含义 | 下面怎么做 |
|---|---|---|
| `SZST0303 张海兰` 8 月卡数已经很多 | 卡已在她名下，缺的是报表 | **跳过第二节**，等 15:00 后直接第三节重算 |
| `SZST0303` 几乎没卡，`SZST0302` 上却有张海兰的时段 | 旧号还占着 | 第二节拉得力；仍不对就记下，等这次升级包回放 |
| 两边都几乎没卡 | 得力还没拉进本系统 | 必须做第二节 |

---

## 二、现在：拉得力（增量，不是全量回放）

页面（推荐）：

1. 登录人事账号
2. **数据接入 → 考勤机数据**
3. 点 **手动同步**（增量，拉到今天）
4. 等到任务 **成功**，不要 FAILED
5. **不要**点「重新计算」

看任务有没有成功：

```bash
"$MYSQL" -h127.0.0.1 -P3306 -uroot --default-character-set=utf8mb4 --table shenzhou_hr -e "
SELECT source.source_type,
       job.status,
       job.accepted_count AS 接受,
       job.quarantined_count AS 隔离,
       job.safe_error_code AS 错误码,
       CONVERT_TZ(job.started_at,  '+00:00', '+08:00') AS 开始上海,
       CONVERT_TZ(job.finished_at, '+00:00', '+08:00') AS 结束上海
FROM attendance_sync_job job
JOIN attendance_source source
  ON source.attendance_source_id = job.attendance_source_id
WHERE source.source_type = 'DELI_CLOUD'
ORDER BY job.created_at DESC
LIMIT 5;
"
```

同步成功后再跑一遍第一节的「八月有效卡数」。  
`SZST0303 张海兰` 应该开始有卡。还没有也没关系，先等 15:00 OA，第三节照样重算；旧 0302 卡要等升级包回放。

---

## 三、15:00 之后：等 OA 整点跑完，再重算 8 月

OA 是上海时区**每个整点**跑，不是启动后再过一小时。15:00 会自己拉一次。

大约 **15:05–15:20**（任务跑完即可，不必卡死 15:00 整）在终端查最近一次 OA：

```bash
export MYSQL_PWD='e0e17f3df673a9f8'
MYSQL=/www/server/mysql/bin/mysql
test -x "$MYSQL" || MYSQL=mysql

"$MYSQL" -h127.0.0.1 -P3306 -uroot --default-character-set=utf8mb4 --table shenzhou_hr -e "
SELECT job.status,
       job.accepted_count AS 接受,
       job.quarantined_count AS 隔离,
       job.safe_error_code AS 错误码,
       CONVERT_TZ(job.started_at,  '+00:00', '+08:00') AS 开始上海,
       CONVERT_TZ(job.finished_at, '+00:00', '+08:00') AS 结束上海,
       TIMESTAMPDIFF(SECOND, job.started_at, job.finished_at) AS 耗时秒
FROM attendance_sync_job job
JOIN attendance_source source
  ON source.attendance_source_id = job.attendance_source_id
WHERE source.source_type = 'OA_ATTENDANCE'
ORDER BY job.created_at DESC
LIMIT 5;
"
```

要看到 **开始上海在 15:00 左右**、`status = SUCCEEDED`（或你们库里的成功态，不是 FAILED）。  
还在跑就等 `finished_at` 有值。FAILED 不要重算，先看错误码。

确认成功后，**只重算江苏神州 2026-08**（脚本默认就是 8 月）：

```bash
# 先看会算哪家
COMPANY_ID=41000000-0000-0000-0000-000000000003 bash /root/recalculate-open-month.sh preview

# 后台跑，防断线。江苏神州 8 月大约十几到几十分钟
COMPANY_ID=41000000-0000-0000-0000-000000000003 \
  nohup bash /root/recalculate-open-month.sh \
  >> /root/recalculate-zhanghailan-202608.log 2>&1 &

tail -f /root/recalculate-zhanghailan-202608.log
```

`Ctrl+C` 只停 tail，不要停后台。看到 `完成` 且 `成功=1` 即可。

没有 `/root/recalculate-open-month.sh` 时，从本仓库拷：

`release-candidates/recalculate-open-month.sh` → 服务器 `/root/recalculate-open-month.sh`，然后 `chmod +x`。

---

## 四、验收

浏览器 **Ctrl+F5**。查询报表 → 考勤明细，公司选江苏神州，工号 `SZST0303`，日期 2026-08。

- 张海兰有行
- 得力有打卡的工作日格子有时刻，不能整月空白/全漏刷
- 姜长波 `SZST0302` 的卡还在他自己名下

库里核对 8 月日事实是否已有上下班：

```bash
"$MYSQL" -h127.0.0.1 -P3306 -uroot --default-character-set=utf8mb4 --table shenzhou_hr -e "
SELECT version.employee_number,
       version.display_name,
       fact.business_date,
       fact.first_punch_at,
       fact.last_punch_at
FROM attendance_report_daily_fact fact
JOIN attendance_report_projection projection
  ON projection.attendance_report_projection_id =
     fact.attendance_report_projection_id
JOIN employee_version version
  ON version.employee_version_id = fact.employee_version_id
WHERE version.employee_number = 'SZST0303'
  AND projection.company_id = '41000000-0000-0000-0000-000000000003'
  AND projection.period_start = '2026-08-01'
  AND projection.status = 'PUBLISHED'
  AND projection.formula_catalog_version = 'FULL_CALCULATION_OA_FORM_HOURS_V8'
ORDER BY projection.published_at DESC, fact.business_date
LIMIT 40;
"
```

`first_punch_at` / `last_punch_at` 有值 = 重算已经吃到卡。

---

## 时间线

```
现在
  第一节 查 0303/0302 八月卡数
  第二节 考勤机数据 → 手动同步得力 → 等到成功

15:00  系统自己跑 OA 整点同步（不用手点）
15:05+ 第三节 确认 OA SUCCEEDED
       然后 recalculate-open-month.sh（只江苏神州）

算完    Ctrl+F5 看 0303 考勤明细
```

旧 0302 卡若仍在姜长波或隔离区，这次同步+重算搬不走，要等升级包里的回放。先把 0303 新卡和 8 月报表算出来。
