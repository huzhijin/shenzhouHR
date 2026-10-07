# 宝塔升级包 20260901-query-range-marriage

对应变更 `query-report-range-marriage-export`。

本包：

- 查询报表去掉「月份」，只留起止日期（本月/上月/近三个月）
- 年假统计表、调休统计表仍选自然年
- 考勤明细继续禁止跨月；抽屉仍是单月日历
- **只改婚假**：周末与节假日不计入小时，格子显示休息日/节假日
- 查询报表导出：全部 / 当前页；分页 50/100/200
- 考勤明细导出仍是签到/签退日历表
- **不改**考勤报表中心「导出当前报表」

不改得力/OA cron。不自动重开关账月。不要回拨 OA 水位。不要跑 `/root/run-oa-resync.sh`。

发布包：`release-candidates/shenzhouhr-release-20260901-query-range-marriage.tar.gz`

## 本机上传

```bash
scp release-candidates/shenzhouhr-release-20260901-query-range-marriage.tar.gz \
    release-candidates/upgrade-query-range-marriage.sh \
    root@服务器:/root/
```

## 服务器

```bash
sha256sum /root/shenzhouhr-release-20260901-query-range-marriage.tar.gz
# 必须是
# 9a5398d8a4272c0666805beed8f88b35b8be846c0b2e450eb04b0d4773191d18

chmod +x /root/upgrade-query-range-marriage.sh
bash /root/upgrade-query-range-marriage.sh
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。

## 部署后重算（必须，按顺序）

关账月不要重开。只算 OPEN 的 2026-08。婚假小时要重算才会变。

```bash
nohup bash -lc '
COMPANY_ID=41000000-0000-0000-0000-000000000003 bash /root/recalculate-open-month.sh
COMPANY_ID=41000000-0000-0000-0000-000000000002 bash /root/recalculate-open-month.sh
COMPANY_ID=41000000-0000-0000-0000-000000000001 bash /root/recalculate-open-month.sh
' >> /root/recalculate-query-range-marriage.log 2>&1 &

tail -f /root/recalculate-query-range-marriage.log
```

公司对应：江苏神州 `…0003`，晟州 `…0002`，昇州 `…0001`。

## 抽查

- 查询报表请假/工时/明细：期间只有起止日期，没有月份
- 年假统计表可以改自然年
- 考勤明细跨月弹「暂不支持跨月」，不提「请先选月份」
- 婚假跨周末：请假统计小时不含周六日；矩阵周六日是休息日
- 查询导出下拉有「导出全部」「导出当前页」；明细 xlsx 仍是签到/签退
- 考勤报表中心「导出当前报表」与改前一致
