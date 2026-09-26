# 宝塔升级包 20260821-pin-ot-roster

本包合并三个会话的改动：

1. 考勤报表钉住快照：第一次打开自动整月核算并落库，之后查询只读这份结果；只有 HR 管理员、系统管理员能点「重新计算」。
2. 过夜加班与报表公式：去掉 06:00 切点、按假别处理周末小时、月度工时只加计薪加班、半天出勤率、虚假加班。
3. 花名册组织树重挂 + 生效日 2026-01-01 + 免打卡名单；报表部门显示「工程一部-MATCH组-MATCH1组」这种拼接。

现网路径不要改：

| 用途 | 路径 |
|---|---|
| 后端 jar | `/opt/shenzhouhr/app/shenzhou-hr.jar` |
| 日志 | `/opt/shenzhouhr/logs` |
| 备份 | `/opt/shenzhouhr/backups` |
| 前端 | `/www/wwwroot/192.168.160.226` |
| 启动脚本 | `/opt/shenzhouhr/start-prod.sh` |

把 tar.gz 传到 `/root` 后，按包内 `部署操作-服务器执行.txt` 执行。

Flyway 会执行：

- `V53__standing_punch_exemption.sql`
- `V54__system_admin_attendance_report_read.sql`
- `V55__attendance_days_half_and_fake_overtime.sql`

随后必须在 `shenzhou_hr` 执行包内：

`db/roster-org-sync-cutover.sql`

不要覆盖现网 `/opt/shenzhouhr/start-prod.sh`。启动后由 HR 管理员对未关账月份点「重新计算」。
