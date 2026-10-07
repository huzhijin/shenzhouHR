# 得力身份回放上线顺序

不得只点「重新计算」。不得改得力 00:00 / 12:00 cron。

## 0. 部署代码

- 后端带 `shenzhouhr.deli.replay-enabled`（默认 `false`）和
  `POST /api/v1/attendance-sources/deli-identity-replay`。
- 环境文件增加 `SHENZHOUHR_DELI_REPLAY_ENABLED=false`，日常保持关闭。
- `SHENZHOUHR_DELI_AUTO_SYNC_CRON` 保持 00:00/12:00，不要改成回放。

## 1. 绑定种子（task 2）

优先走回放接口的 `seedBindings=true`（会拉得力人员目录交叉验证雪花 id）。

也可在目标库预览/写入 SQL（工号+姓名对不上的不会写）：

```bash
# preview
mysql --default-character-set=utf8mb4 shenzhou_hr \
  < deploy/mysql/deli-conflict-identity-bindings.sql

# apply
mysql --default-character-set=utf8mb4 shenzhou_hr \
  -e "SET @apply := 1; SOURCE deploy/mysql/deli-conflict-identity-bindings.sql;"
```

李扬雪花 id `947095089162633216` 已在七月映射中，不必猜。

调用人需要 `ATTENDANCE_SOURCE:RUN`。若 `seedBindings=true` 还需要 `ATTENDANCE_SOURCE:CONFIGURE`；若 `recalculate=true` 还需要 `ATTENDANCE_REPORT:REFRESH`。

## 2. 打开开关并回放（task 3）

默认窗：`2026-08-01`～`2026-08-13`（上海）。消化隔离积压时设 `THROUGH_TODAY=true`。

```bash
# 宝塔 Java 项目环境临时打开
# SHENZHOUHR_DELI_REPLAY_ENABLED=true
# 重启 Java 项目后再跑：

CONFIRM=YES \
BASE_URL=http://127.0.0.1:8080 \
USERNAME=... \
PASSWORD=... \
THROUGH_TODAY=false \
bash deploy/baota/scripts/deli-identity-replay.sh
```

回放不推进 CHECKIN/KQ 水位。响应里的 `stillQuarantined` 必须看：reason、empno、雪花 id。

跑完把 `SHENZHOUHR_DELI_REPLAY_ENABLED` 改回 `false` 并重启。

## 3. 重算（task 4.1）

回放接口默认 `recalculate=true`，会重算回放碰到的公司月（江苏神州 2026-08）。
若当时关了重算，再：

```http
POST /api/v1/attendance-reports/recalculate
{"period":"2026-08","companyId":"<江苏神州>"}
```

## 4. 互补 Excel 验收（task 4.2–4.5）

黄凯→`SZST0667`，张晨阳→`SZST0663`，于跃→`SZST0671`。不改 Excel、不改花名册工号。

```bash
BASE_URL=http://127.0.0.1:8080 \
USERNAME=... \
PASSWORD=... \
COMPANY_ID=<江苏神州> \
PERIOD=2026-08 \
bash deploy/baota/scripts/compare-deli-month-report.sh \
  "/path/to/考勤月报.xlsx" \
  "/path/to/月度汇总表.xlsx"
```

闸门：

- 彭伟 / 陆玉蕾 / 李扬 / 王善源 / 居军 / 张晨阳：8/1–8/12 表有卡的日子，矩阵不得全空。
- 赵艺娴 8/1–8/13 自己的卡还在。
- 脚本仍列出的 `SZST067x–070x` DELI_ONLY **不计入本 change 已清**。

退出码 2 = 验收闸门失败。
