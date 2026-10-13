# 宝塔升级包 20260903-ot-leave-excel

义务加班单独颜色和筛选、调休+事假不再冒出汇总事假、每日加班/工时统计导出带 Excel 公式。周几仍是数字（周一=1 … 周日=7）。

换 jar 和前端。不覆盖 `start-prod.sh`。不要回拨 OA 水位。不要改得力/OA cron。不要重跑 `20260903-szt-oa-rematch`。

发布包：`release-candidates/shenzhouhr-release-20260903-ot-leave-excel.tar.gz`

## 1. 本机上传

```bash
scp /Users/huzhijin/Downloads/shenzhouHR/release-candidates/shenzhouhr-release-20260903-ot-leave-excel.tar.gz \
    /Users/huzhijin/Downloads/shenzhouHR/release-candidates/upgrade-ot-leave-excel.sh \
    root@服务器:/root/
```

## 2. 服务器部署

```bash
sha256sum /root/shenzhouhr-release-20260903-ot-leave-excel.tar.gz
# 必须是
# 62aafcee6945152172d43f2ae41a9078a3029dd28197c9ab073dd4e5901a00d7

chmod +x /root/upgrade-ot-leave-excel.sh
bash /root/upgrade-ot-leave-excel.sh
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。

## 3. 作废误建叶剑 SZST0567（可重复）

现网已作废过一次。部署后再跑一次是安全的，不会动正式叶剑 `SZSZ0000`。

```bash
python3 /root/void-szst0567-yejian.py
APPLY=1 python3 /root/void-szst0567-yejian.py
```

人员查询搜「叶剑」应只剩 `SZSZ0000`。不要再建 `SZST0567`。

## 4. 整月重算江苏神州 2026-08（一次）

公式和请假去重要靠重算后的报表数据。只算江苏。不要按人重算。

```bash
COMPANY_ID=41000000-0000-0000-0000-000000000003 \
  bash /root/recalculate-open-month.sh
```

不要 `run-oa-resync.sh`，不要回拨 OA 水位，不要改 cron。

## 5. 提出问题的解决步骤

按顺序做完第 2、3、4 步后：

| 你提的问题 | 怎么解决 | 你怎么验 |
|---|---|---|
| 义务加班要算工时、不算加班费，单独颜色，筛选要能用 | 本包前端+后端。格子紫色 `#6b4e9b`。筛选「义务加班」只出这类小时。工时公式含义务加班，加班费合计不含 | 查询报表每日加班：筛选义务加班有行；混天格子分色；工时统计有义务加班列 |
| 张晓冬 8/31 调休+事假又冒出一条 UNKNOWN 3.5h 事假 | 本包去掉「盖住明细的汇总请假单」。重算 8 月后生效 | 请假统计：张晓冬 `SZST0548` 8/31 只留事假 1.0h + 调休 2.5h，不再有 3.5h |
| 每日加班、工时统计导出带公式 | 本包 Excel：平时/周末/节假日 `SUM` 日期列；实际出勤 `应出勤+加班+义务加班−请假−年假+换调休−实际调休` | 导出后点开公式栏，改一天小时数合计会跟着变 |
| 导出要有周几，查询报表里有 | **现网已经有**。每日加班第 2 行是数字 1–7（周一=1 … 周日=7），按你的要求保持数字，不改成「星期一」 | 导出每日加班看第 2 行，8 月 1 日应是 `6` |
| 花名册漏人（韩迎平等） | 韩迎平 `SZST0560`、张晓龙 `SZST0573` 已建档。叶剑正式工号是昇州 `SZSZ0000`，误建 `SZST0567` 已作废。崔雨 `SZST0721`、张自豪 `SZST0722` 本来就在库里（离职后今天人员查询看不见），**不要再建** | 搜韩迎平/张晓龙能查到；搜叶剑只剩 SZSZ0000 |
| 崔雨、张自豪：工号冲突、人员查询查不到 | 人在库里、工号占着，所以不能新建；员工版本关到离职次日，所以今天搜不到。8 月仍用原档案出到离职当天 | 不要点新建。崔雨出到 8/21，张自豪出到 8/28 |
| 8 月应出勤/加班/请假格子（周末晚餐、大连全勤、离职截到当天等） | 上一包已上。本包重算 8 月后，义务加班和请假去重叠上去 | 重算完成后再看报表中心和查询报表 |

## 6. 不要做的

- 不要再建叶剑 `SZST0567`、崔雨、张自豪
- 不要把叶剑从 `SZSZ0000` 改成别的工号
- 不要重跑 `upgrade-szt-oa-rematch.sh` 顶掉本包
- 不要回拨 OA 水位、不要改得力/OA cron
- 晟州丁昊/汤嘉鸣本包不补；得力 8 月认人回放仍关着，韩迎平/张晓龙的打卡可能还对不齐，要开回放再说

## 校验和

```
62aafcee6945152172d43f2ae41a9078a3029dd28197c9ab073dd4e5901a00d7
```
