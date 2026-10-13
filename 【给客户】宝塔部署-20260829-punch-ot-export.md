# 宝塔升级包 20260829-punch-ot-export

对应变更 `report-punch-ot-export-followup`（考勤报表打卡/加班/导出后续）。

本包：

- 过夜离开与次日早卡按 **06:00** 拆开：`次日 00:14` 仍归前一天，`07:56` 记次日上班
- OA 加班结束晚于末卡：出「加班结束晚于打卡」，小时截到末卡；无末卡则 0 小时
- 跨夜（含周末/节假日）小时全部记在加班开始日，财务加班同样
- 同一人同一加班时段只留一条
- 「未报加班」仅 18:30 之后无单才出
- 每日加班筛选「加班费 / 转调休」只藏列、不删行
- 报表中心导出真实 xlsx，不再写「演示文件已导出」；月矩阵按当前屏幕导出
- 休息日无班次不再涂成漏刷漏刷
- 公式 **V8**

不改得力/OA cron。不自动重开关账月。不要回拨 OA 水位。不要跑 `/root/run-oa-resync.sh`。

发布包：`release-candidates/shenzhouhr-release-20260829-punch-ot-export.tar.gz`

## 本机上传

```bash
scp release-candidates/shenzhouhr-release-20260829-punch-ot-export.tar.gz \
    release-candidates/upgrade-punch-ot-export.sh \
    root@服务器:/root/
```

## 服务器

```bash
sha256sum /root/shenzhouhr-release-20260829-punch-ot-export.tar.gz
# 必须是
# 69662f69debfcd69f321a03c279e16605b6d99c7277079a0e247244d2b524901

bash /root/upgrade-punch-ot-export.sh
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。

## 部署后重算（必须，按顺序）

关账月不要重开。只算 OPEN 的 2026-08。

```bash
nohup bash -lc '
COMPANY_ID=41000000-0000-0000-0000-000000000003 bash /root/recalculate-open-month.sh
COMPANY_ID=41000000-0000-0000-0000-000000000002 bash /root/recalculate-open-month.sh
COMPANY_ID=41000000-0000-0000-0000-000000000001 bash /root/recalculate-open-month.sh
' >> /root/recalculate-punch-ot-export.log 2>&1 &

tail -f /root/recalculate-punch-ot-export.log
```

公司对应：江苏神州 `…0003`，晟州 `…0002`，昇州 `…0001`。

## 若晟州/昇州仍几乎全月漏刷

绑定在、打卡事件不在时，先身份回放再重算那一家（不要改 cron）：

```bash
THROUGH_TODAY=true SKIP_COMPARE=1 \
  COMPANY_ID=41000000-0000-0000-0000-000000000002 \
  bash /root/run-deli-identity.sh

COMPANY_ID=41000000-0000-0000-0000-000000000002 bash /root/recalculate-open-month.sh

THROUGH_TODAY=true SKIP_COMPARE=1 \
  COMPANY_ID=41000000-0000-0000-0000-000000000001 \
  bash /root/run-deli-identity.sh

COMPANY_ID=41000000-0000-0000-0000-000000000001 bash /root/recalculate-open-month.sh
```

## 抽查

- 考勤报表中心「导出当前报表」下载 xlsx，状态不是「演示文件已导出」
- 吴根银次日 `07:56` 记在次日上班；前一天晚上没卡则下班漏刷
- 金玉亮 `次日 00:14` 仍在前一天下午格
- 李尹 8/8 `09:30–20:00` 只一行，小时截到末卡 18:16，并有「加班结束晚于打卡」
- 18:05 无单不出未报加班；18:31 无单出未报加班
- 每日加班选「加班费」只藏转调休列，行还在
- 晟州 周步新 / 昇州 赵俊君：有卡的工作日不再整月漏刷；周六无班次是休息日
