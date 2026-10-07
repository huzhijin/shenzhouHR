# 查询报表 / 人事改格子 / 8 月关账数据

部署 jar 与前端后，江苏神州 `41000000-0000-0000-0000-000000000003` 做一次 2026-08 整月重算，离职截断、张海兰回放、入职当天 08:29 共用这次。

不要改 OA `0 0 * * * ?`、得力 `0 0 0,8,12,18 * * ?`、不要改 0 点/12 点自动重算。

## 1. 关 8 月离职任职

预览并执行 `deploy/mysql/close-2026-08-jiangsu-leavers.sql`。

崔雨、张自豪若 SELECT 人数不是 1，脚本不会猜，记下后人工处理。

## 2. 张海兰 8 月旧 0302 卡

先跑 `deploy/mysql/replay-zhanghailan-august-0302.sql` 看 0303 是否已有卡。

若 8 月卡仍挂在 `SZST0302` 且姓名是张海兰：

```bash
python3 /root/fix-zhanghailan-august-punches.py
APPLY=1 python3 /root/fix-zhanghailan-august-punches.py
```

姜长波继续占 `SZST0302`。不要跑 `run-deli-identity.sh`。

## 3. 整月重算一次

等当天 OA 整点任务 SUCCEEDED 后再算。只算 8 月：

```bash
COMPANY_ID=41000000-0000-0000-0000-000000000003 \
nohup bash /root/recalculate-open-month.sh
```

## 4. 验收

- 张海兰 `SZST0303` 8 月考勤明细工作日有卡
- 名单内离职人月度工时统计表不是整月（陈柏宇不含 8/8 起；杜超含 8/31）
- 武汉/大连 8 月异常总览为空；7 月仍出
- 异常总览没有未报加班、长时在岗待审；虚假加班显示加班异常
- 入职当天无上班卡显示 08:29，不显示漏刷
