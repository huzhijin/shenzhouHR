# 2026-09-01 query-stats 升级

发布包：`release-candidates/shenzhouhr-release-20260901-query-stats.tar.gz`

SHA256：`75430cbf71e0512c37451ca74450ea37c683b399d11e8eec5868fc1ee65cddbe`

升级脚本：`release-candidates/upgrade-query-stats.sh`

客户说明：`【给客户】宝塔部署-20260901-query-stats.md`

含 Flyway V66（`attendance_hr_punch_adjustment.day_types`）。启动脚本里手工迁列，不覆盖 `start-prod.sh`。

数据 SQL（部署后人工跑，最后再重算）：

- `deploy/mysql/close-2026-08-jiangsu-leavers.sql`
- `deploy/mysql/replay-zhanghailan-august-0302.sql`（预览）+ `scripts/fix-zhanghailan-august-punches.py`
- `deploy/mysql/zhangheng-shengzhou-outing-202608.sql`

不要改 OA/得力 cron。
