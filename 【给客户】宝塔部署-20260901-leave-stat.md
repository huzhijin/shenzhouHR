# 宝塔升级包 20260901-leave-stat（年假/调休统计 500）

覆盖当前已部署的 `shenzhouhr-release-20260901-latest.tar.gz` 后端 jar。

只换 jar，不覆盖 `start-prod.sh`，不重算。

原因：年假统计表 / 调休统计表读 `employee.onboard_date`（含 `0000-00-00` 时 MySQL 直接报错），月用量用 `CONVERT_TZ`。额度表能开是因为它不读这两项。

发布包：`release-candidates/shenzhouhr-release-20260901-leave-stat.tar.gz`

## 本机上传

```bash
scp release-candidates/shenzhouhr-release-20260901-leave-stat.tar.gz \
    release-candidates/upgrade-leave-stat.sh \
    root@服务器:/root/
```

## 服务器

```bash
sha256sum /root/shenzhouhr-release-20260901-leave-stat.tar.gz
# 必须是
# 0c08e2c42dd482c7b2a76014880f5b5b890acb047411b8356ab10bc2907b38d2

chmod +x /root/upgrade-leave-stat.sh
bash /root/upgrade-leave-stat.sh
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。

验证：查询报表 → 年假统计表、调休统计表能出表，不再显示「服务暂时不可用」。

不要回拨 OA 水位。不要跑 `run-oa-resync.sh`。不要改得力/OA cron。不要重开关账月。
