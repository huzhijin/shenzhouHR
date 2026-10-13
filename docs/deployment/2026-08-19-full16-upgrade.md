# 宝塔升级包 20260819-full16

本包合并两段改动：

1. 报表部门不显示 UUID、默认选神州半导体、正常打卡不再叠漏刷/旷工、OA 时间按上海墙上时间入库（V51 把已入库错时减 8 小时）。
2. 得力云考勤 KQ 与现有 CHECKIN 并行增量同步。KQ 拉接口返回的**全部考勤机**（科技园 1/2 号楼、总部 1/2 号门，以及其它 13750C），不是只拉科技园。

## 升级

按现网路径解压覆盖后重启 Java 服务。Flyway 会执行：

- `V51__correct_oa_naive_datetime_utc_shift.sql`
- `V52__deli_kq_watermark.sql`

若现网 Flyway 未开，请在 `shenzhou_hr` 上手动执行上述两个 SQL（顺序不可反）。

## 第一次拉 KQ 8 月期初

**不要重置 CHECKIN 水位，不要调用 `checkin_query_init`。**

在 `shenzhou_hr` 执行包内：

`deploy/baota/mysql/seed-kq-august-watermark.sql`

然后等得力整点任务，或在考勤来源页手动跑一次得力同步。之后 KQ 与 CHECKIN 都会按各自游标每天增量。
