# 宝塔升级包 20260902-weekend-ot-roster

周末加班晚餐窗、花名册/高管/大连武汉全勤、查询月度工时对齐报表中心、加班按实际打卡（8 月忘打下班卡仍出单据小时但报异常）、8 月离职按工号截到当天。

本包另含：武汉/大连 8 月加班请假外出按单据色；离职当天无下班卡赋班结束后 1 分钟、不算漏签。

换 jar 和前端。不覆盖 `start-prod.sh`。不要回拨 OA 水位。不要改得力/OA cron。

发布包：`release-candidates/shenzhouhr-release-20260902-weekend-ot-roster.tar.gz`

## 1. 本机上传

```bash
scp release-candidates/shenzhouhr-release-20260902-weekend-ot-roster.tar.gz \
    release-candidates/upgrade-weekend-ot-roster.sh \
    root@服务器:/root/
```

## 2. 服务器部署

```bash
sha256sum /root/shenzhouhr-release-20260902-weekend-ot-roster.tar.gz
# 必须是
# d802fc93805d4012b1ba56842bf84cfe0d988f46b4a0903d236528e7b8afb87a

chmod +x /root/upgrade-weekend-ot-roster.sh
bash /root/upgrade-weekend-ot-roster.sh
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。

## 3. 关 8 月离职（先预览）

```bash
python3 /root/close-2026-08-jiangsu-leavers.py
# 确认 15 个工号（含崔雨 SZST0721、张自豪 SZST0722）后
APPLY=1 python3 /root/close-2026-08-jiangsu-leavers.py
```

不要关黄兆隆、唐家轩、张衡、赵子奇。谭钊无花名册工号，不要建档。

## 4. 8 月新入职单据：OA continue，不回拨水位

```bash
python3 /root/list-aug-2026-new-hire-oa.py
bash /root/run-oa-continue.sh
```

看完单据状态后再重算。不要 `run-oa-resync.sh`，不要改 OA cursor。

## 5. 重算江苏神州 2026-08 OPEN

这次小时口径和格子颜色变了，**必须重算 8 月**，否则颜倩/李鑫/全勤/离职下班卡都还是旧数。

```bash
COMPANY_ID=41000000-0000-0000-0000-000000000003 \
  bash /root/recalculate-open-month.sh
```

聚能 8 月新入职若也要对：`COMPANY_ID=41000000-0000-0000-0000-000000000002`。

## 抽检

| 人/现象 | 换包+重算后 |
|---|---|
| 颜倩 8/29 09:00–18:00 | 加班 **8** 不是 7.5 |
| 李鑫 8/26 末卡 20:02、单到 21:00 | 加班 **1.5** 不是 2.5 |
| 8 月有加班单忘打下班卡 | 小时仍按单据，**异常+漏签**（和得力对） |
| 9 月起同样情况 | 小时 **0**、异常、漏签，补签后再出实际小时 |
| 陈觉晓/朱培文/熊都 | 月度工时有应出勤，格子白 |
| 霍岩 8 月 | 加班日加班色，请假日出假色，其它工作日全勤不漏刷；**9 月按打卡** |
| 武汉/大连 8 月加班请假外出 | 单据色，不被全勤白格盖掉 |
| 外出+加班 | 格子跟加班单时刻；工作日加班扣班次 |
| 查询「月度工时统计表」 | 与报表中心同一人、同一小时 |
| 姚韩 / 刘至宽 | 出到 8/21 |
| 徐利民 | 出到 8/11；离职当天无下班卡显示班结束后 1 分钟，不漏签 |
| 张泽 | 出到 8/28 |
| 崔雨 | 只算到 8/21 |
| 黄兆隆 / 唐家轩 | 8 月仍在职 |
| 8 月新入职 | 请假/加班/外出状态正常 |

## 校验和

```
d802fc93805d4012b1ba56842bf84cfe0d988f46b4a0903d236528e7b8afb87a
```
