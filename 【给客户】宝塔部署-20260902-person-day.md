# 宝塔升级包 20260902-person-day（保存并重算只算这个人）

查询报表里点「保存并重算」，以前会按整公司整月加载和重写 OA，所以一个人一天也要等很久。

本包改为：

- 只读、只算**这个人**，日期是当天 ±1 天（夜班跨天）
- 其他人、这个人其余日期从上一版报表复制
- 不再整表重写全公司 OA

报表中心「整月重算」没改。换包后**不要**再点昇州/江苏整月重算。

张衡 8/11、8/26 外出行已经写进人事调整。换包后对那两天各点一次保存并重算即可。

换 jar 和前端。不覆盖 `start-prod.sh`。不要回拨 OA 水位。不要改得力/OA cron。

发布包：`release-candidates/shenzhouhr-release-20260902-person-day.tar.gz`

## 本机上传

```bash
scp release-candidates/shenzhouhr-release-20260902-person-day.tar.gz \
    release-candidates/upgrade-person-day.sh \
    root@服务器:/root/
```

## 服务器

```bash
sha256sum /root/shenzhouhr-release-20260902-person-day.tar.gz
# 必须是
# 13c34637a3a4db1187730e02f9896227d74dceaa81e62eeda315694a72accf26

chmod +x /root/upgrade-person-day.sh
bash /root/upgrade-person-day.sh
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。

## 部署后

不要跑 `/root/recalculate-open-month.sh`。不要叠整月核算。

张衡 SZSZ0003：8 月 11 日、8 月 26 日各保存并重算一次。保存转圈应在几秒到十几秒内结束，不要连点。
