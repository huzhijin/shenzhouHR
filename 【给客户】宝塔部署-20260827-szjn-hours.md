# 2026-08-27 晟州负期初

发布包：`release-candidates/shenzhouhr-release-20260827-szjn-hours.tar.gz`

晟州整月钉不住，是因为有人年假/调休期初小时为负，报表列 `opening_hours` 是 UNSIGNED。本包 Flyway V64 改为可写负数。启动会迁库。只重算上海晟州聚能。昇州、神州、芯越今天已是 V6。

## 本机上传

```bash
scp release-candidates/shenzhouhr-release-20260827-szjn-hours.tar.gz \
    release-candidates/upgrade-szjn-hours.sh \
    root@服务器:/root/
```

## 服务器

```bash
sha256sum /root/shenzhouhr-release-20260827-szjn-hours.tar.gz
# 必须是
# 5ac87bb261e73eed05dfff7a4af809aadd1fb7c03ecf648356152f377d226e37

bash /root/upgrade-szjn-hours.sh
```

健康检查通过后：

```bash
nohup env COMPANY_ID=41000000-0000-0000-0000-000000000002 \
  bash /root/recalculate-open-month.sh \
  >> /root/recalculate-failed.log 2>&1 &

tail -f /root/recalculate-failed.log
```
