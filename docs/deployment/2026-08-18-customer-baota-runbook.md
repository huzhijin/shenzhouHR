# 2026-08-18 客户宝塔部署手册（实时考勤）

目标：把当前制品部署到客户机，备份后把 `shenzhou_hr` 从 **Flyway V35** 迁到 **V49**，打开得力/OA 只读同步，再对账。

**不要把 App-Key / App-Secret / 数据库口令写进仓库。** 启动命令里的密钥只放宝塔终端或 `/opt/shenzhouhr/conf/start.sh`（权限 `0600`）。

## 0. 你之前的启动参数为什么同步失败

旧命令用了**错误前缀**，得力和 OA 适配器根本不会启用：

| 旧参数（无效） | 正确参数 |
|---|---|
| `--deli-eplus.enabled` | `--shenzhouhr.integrations.deli-eplus.enabled` |
| `--deli-eplus.app-key` | `--shenzhouhr.integrations.deli-eplus.app-key` |
| `--deli-eplus.app-secret` | `--shenzhouhr.integrations.deli-eplus.app-secret` |
| `--shenzhouhr.oa.datasource.url` | `--shenzhouhr.integrations.oa-mysql.jdbc-url` |
| `--shenzhouhr.oa.datasource.username` | `--shenzhouhr.integrations.oa-mysql.username` |
| `--shenzhouhr.oa.datasource.password` | `--shenzhouhr.integrations.oa-mysql.password` |
| `--spring.flyway.enabled=false` | 迁库时必须 **true**，否则永远停在 V35 |

这就是客户机「得力任务失败 / OA 单据 0 条」的主要原因之一。

## 1. 上传制品

把发布包解到例如 `/opt/shenzhouhr/release/20260818/`。至少包含：

- `backend/shenzhou-hr.jar`
- `web/`（前端静态）
- `db/migration/`（V1–V49）
- 本手册

替换正在跑的 jar：

```bash
cp backend/shenzhou-hr.jar /opt/shenzhouhr/app/shenzhou-hr.jar
```

前端按现网 nginx 根目录覆盖 `web/`。

## 2. 停服务并备份

```bash
ps aux | grep shenzhou-hr.jar | grep -v grep
kill -15 <PID>
ps aux | grep shenzhou-hr.jar

mkdir -p /opt/shenzhouhr/backup
mysqldump -uroot -p --single-transaction --routines --triggers \
  shenzhou_hr > /opt/shenzhouhr/backup/shenzhou_hr-before-v49-$(date +%Y%m%d%H%M).sql
```

确认 dump 非空后再往下。

## 3. 核对当前 Flyway

```sql
SELECT version, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 15;

SELECT version, success
FROM flyway_schema_history
WHERE version IN ('47', '48', '49');
```

现在应仍是最高 **35**。迁完必须有 **49** 且 `success=1`。

## 4. 一次性前向迁移（V36–V49）

用**同一套**业务库账号、**打开 Flyway** 启动一次。日志里应出现 `Migrating schema to version "36"` … `"49"`。

迁完再执行：

```sql
-- 补卡表
SHOW TABLES LIKE 'punch_correction_request';

-- 出勤天
SHOW COLUMNS FROM attendance_report_daily_fact LIKE '%attendance_day%';

-- SYSTEM 主体
SELECT principal_id, status FROM auth_principal WHERE principal_id = 'SYSTEM';

-- 销假流水号
SHOW COLUMNS FROM oa_attendance_document LIKE '%serial%';
```

四项都在，才能谈补卡配额、定时同步、销假按流水号匹配。

## 5. 正式启动（模板，密钥自己填）

把下面保存为 `/opt/shenzhouhr/conf/start-prod.sh`，`chmod 700`。

正确属性名见文首表格。自动同步默认在 Java 里是关的，部署后必须显式打开。

禁止：`checkin_query_init`（代码不会发）、把 key/secret 提交到 git。

人员绑定：`POST /v2.0/employee/query` 的 `employee_num` → 本地 `employee_number`，再按 `user_id` 吃打卡。  
部门：`POST /v2.0/department/query` 拉全量对照（约 55 个）。

验收条数（联调观察值，不是硬编码）：

- 部门约 55
- 人员约 597（约 580 有 SZST 工号）
- 打卡 `next_id=0` 约 8900，其中 8 月约 7984
- OA 单据不能再是 0

## 6. 同步与对账

1. 登录后打开「考勤机数据」：看最近失败原因，点「手动同步」。
2. 「同步记录」确认得力/OA 任务成功或仅部分隔离。
3. 「OA 单据」条数 > 0。
4. 打开「考勤报表」2026-08，期间未关会标「暂算」。班次应是真实名。
5. 抽 3–5 人：打卡、请假/销假、加班。

## 7. 本轮不要做

- 五千人性能门（7.1）
- 全量门禁同一 commit（7.2）
- 对账通过前不要宣称不可变宝塔包已验收（7.3 制品可先用，验收等 6.7）
- 不要排预发/生产窗口（7.4 / Q16=B）

## 8. 反馈菜单

本人考勤反馈没有独立后端，生产菜单已隐藏。直达 `/me/feedback` 只显示「尚未开通」，不是假数据页。
