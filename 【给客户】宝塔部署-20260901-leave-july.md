# 宝塔升级包 20260901-leave-july（年假/调休统计期初与 8 月）

覆盖当前已部署的 `20260901-latest` / `leave-stat` 后端 jar 和前端。

只换 jar 和 web，不覆盖 `start-prod.sh`，不重算。

年假汇总文件里的数字按 **小时**（不是天数）。

显示规则：

- **期初** = 7 月底（8 月 1 日开始前）。现在库里的额度是 8 月底算完的结果，页面用「当前可休 + 8 月已休 − 8 月加班转入」回推 7 月底。
- **8 月已休 / 加班转入** = 已发布考勤核算里保存的 OA 单据，按月汇总。查询不再现算考勤。
- **可休** = 当前额度（8 月底核算结果）。

发布包：`release-candidates/shenzhouhr-release-20260901-leave-july.tar.gz`

## 本机上传

```bash
scp release-candidates/shenzhouhr-release-20260901-leave-july.tar.gz \
    release-candidates/upgrade-leave-july.sh \
    root@服务器:/root/
```

## 服务器

```bash
sha256sum /root/shenzhouhr-release-20260901-leave-july.tar.gz
# 必须是
# a3ed17589b6055b039ecb51b58d4559c96c25a16e34ed186693bff051894c932

chmod +x /root/upgrade-leave-july.sh
bash /root/upgrade-leave-july.sh
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。

不要回拨 OA 水位。不要跑 `run-oa-resync.sh`。不要改得力/OA cron。不要重开关账月。
