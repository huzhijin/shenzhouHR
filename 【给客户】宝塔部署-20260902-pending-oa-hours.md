# 宝塔升级包 20260902-pending-oa-hours

**先部署，再对账，再按人重算。** 不先换包，重算也还是旧口径（无卡加班仍是 0，每日加班仍空）。

本包：

- 审批中 OA 盖漏刷，并进财务加班 / 加班明细
- 无卡加班单按 OA 整点/半点出小时（DC 部格子有加班、每日加班没有，就是这个）
- 加班小时只保留整数或 0.5，不再打出 `63.400000000000006`
- 月度工时按花名册出人
- 带对账脚本

不覆盖 `start-prod.sh`。不要回拨 OA 水位。不要改得力/OA cron。不要点报表中心整月重算。

发布包：`release-candidates/shenzhouhr-release-20260902-pending-oa-hours.tar.gz`

## 1. 本机上传

```bash
scp release-candidates/shenzhouhr-release-20260902-pending-oa-hours.tar.gz \
    release-candidates/upgrade-pending-oa-hours.sh \
    root@服务器:/root/
```

## 2. 服务器部署

```bash
sha256sum /root/shenzhouhr-release-20260902-pending-oa-hours.tar.gz
# 必须是
# e9e94ec7e97f6ded58f49b784e1e77e8a530ce68d53c01853c89223dead1ea7f

chmod +x /root/upgrade-pending-oa-hours.sh
bash /root/upgrade-pending-oa-hours.sh
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。

## 3. 部署后再对账、再重算

```bash
python3 /root/diagnose-pending-oa-and-roster.py
```

若报 `ERROR 1045`（脚本默认读应用库账号；你这台 root 密码与应用库密码不同），用 MySQL root：

```bash
MYSQL_USER=root MYSQL_PWD='你的mysql_root密码' python3 /root/diagnose-pending-oa-and-roster.py
```

确认张衡 `SZSZ0003` 8/11、8/26 外出仍在「应保留」。

然后只对脚本列出的人重算 **2026-08**（查询报表里对该人点保存并重算，或按人接口 `employeeIds` + `fromDate=2026-08-01` + `toDate=2026-08-31`）。**不要**跑 `/root/recalculate-open-month.sh`，不要点整公司整月。

## 抽检

| 现象 | 换包+按人重算后 |
|---|---|
| DC 部格子有加班、每日加班没有 | 每日加班应出现 28–31 等天的小时 |
| 居军 8/19–20 | 外出，不要漏刷 |
| 张衡 8/11、8/26 | 仍是外出 |
| 叶剑 SZSZ0000 | 昇州月度工时有行 |
| 每日加班不选部门 | 该公司全部有加班的人 |
| 加班合计 | 不要 `63.400000000000006` |
