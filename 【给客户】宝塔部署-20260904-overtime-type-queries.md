# 宝塔升级包 20260904-overtime-type-queries

本包覆盖上一包 `20260903-ot-leave-excel`，并加上三张查询：每日加班费、每日义务加班、每日调休。只换这一包，不要再装 `ot-leave-excel`。

换 jar 和前端。不覆盖 `start-prod.sh`。不要回拨 OA 水位。不要改得力/OA cron。

发布包：`release-candidates/shenzhouhr-release-20260904-overtime-type-queries.tar.gz`

## 1. 本机上传

```bash
scp /Users/huzhijin/Downloads/shenzhouHR/release-candidates/shenzhouhr-release-20260904-overtime-type-queries.tar.gz \
    /Users/huzhijin/Downloads/shenzhouHR/release-candidates/upgrade-overtime-type-queries.sh \
    root@服务器:/root/
```

## 2. 服务器安装

```bash
sha256sum /root/shenzhouhr-release-20260904-overtime-type-queries.tar.gz
# 必须是
# b00c592b2a085410b36b98d12205de5e7c3bea25726fa7a34fdd50ccea9f1550

chmod +x /root/upgrade-overtime-type-queries.sh
bash /root/upgrade-overtime-type-queries.sh
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。

## 3. 要不要重算

| 目的 | 要不要重算 |
|------|------------|
| 只看三张新查询（加班费 / 义务加班 / 调休） | **不用。** 读现有 8 月核算结果，换包 + Ctrl+F5 就能查 |
| 义务加班颜色、张晓冬覆盖请假、导出公式、韩迎平/张晓龙 8 月出人 | **要。** 若江苏 8 月还没为这些重算过，换包后再算一次 |

现网还没上过 `ot-leave-excel` 的，**建议换包后重算江苏 8 月一次**：

```bash
COMPANY_ID=41000000-0000-0000-0000-000000000003 \
  bash /root/recalculate-open-month.sh
```

已经为义务加班/漏人重算过、只差三张新表的，跳过这步。

不要 `run-oa-resync.sh`，不要回拨 OA 水位，不要改 cron。

## 4. 换包后能验的

- 查询报表菜单：每日加班查询旁边有 **每日加班费查询、每日义务加班查询、每日调休查询**
- 加班费表只有加班费列，义务加班表只有义务加班列，调休表只有转调休列
- 每日加班第 2 行仍是星期数字 1–7
- 导出每日加班/工时统计带公式（重算不是公式的前提，换包后重新导出即可）

## 校验和

```
b00c592b2a085410b36b98d12205de5e7c3bea25726fa7a34fdd50ccea9f1550
```
